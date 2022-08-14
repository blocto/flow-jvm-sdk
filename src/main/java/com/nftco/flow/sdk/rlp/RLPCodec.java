package com.nftco.flow.sdk.rlp;

import lombok.NonNull;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class RLPCodec {
    public static <T> T decode(byte[] data, Class<T> clazz) {
        RLPElement element = RLPElement.fromEncoded(data);
        return decode(element, clazz);
    }

    public static <T> T decode(byte[] data, Class<T> clazz, RLPContext context) {
        RLPElement element = RLPElement.fromEncoded(data);
        return decode(element, clazz, context);
    }

    public static <T> T decode(RLPElement element, Class<T> clazz) {
        return decode(element, clazz, RLPContext.EMPTY);
    }

    public static <T> T decode(RLPElement element, Class<T> clazz, RLPContext context) {
        if (clazz == RLPElement.class) return (T) element;
        if (clazz == RLPList.class) return (T) element.asRLPList();
        if (clazz == RLPItem.class) return (T) element.asRLPItem();
        RLPDecoder<T> decoder = RLPUtils.getAnnotatedRLPDecoder(clazz);
        if (decoder != null) return decoder.decode(element);
        decoder = context.getDecoder(clazz);
        if (decoder != null) return decoder.decode(element);
        if (clazz == boolean.class || clazz == Boolean.class) return (T) Boolean.valueOf(element.asBoolean());
        if (clazz == Byte.class || clazz == byte.class) {
            return (T) Byte.valueOf(element.asByte());
        }
        if (clazz == Short.class || clazz == short.class) {
            return (T) Short.valueOf(element.asShort());
        }
        if (clazz == Integer.class || clazz == int.class) {
            return (T) Integer.valueOf(element.asInt());
        }
        if (clazz == Long.class || clazz == long.class) {
            return (T) Long.valueOf(element.asLong());
        }
        if (clazz == byte[].class) {
            return (T) element.asBytes();
        }
        // String is non-null, since we cannot differ between null empty string and null
        if (clazz == String.class) {
            return (T) element.asString();
        }
        // big integer is non-null, since we cannot differ between zero and null
        if (clazz == BigInteger.class) {
            return (T) element.asBigInteger();
        }
        if (element.isNull()) return null;
        if (clazz.isArray()) {
            Class elementType = clazz.getComponentType();
            Object res = Array.newInstance(clazz.getComponentType(), element.size());
            for (int i = 0; i < element.size(); i++) {
                Array.set(res, i, decode(element.get(i), elementType));
            }
            return (T) res;
        }
        // cannot determine generic type at runtime
        if (
                RLPUtils.isContainer(clazz)
        ) {
            return (T) decodeContainer(element, Container.fromClass(clazz), context);
        }
        T o = RLPUtils.newInstance(clazz);
        List<Field> fields = RLPUtils.getRLPFields(clazz);
        if (fields.size() == 0) throw new RuntimeException("no encodable field of " + clazz.getName() + " found");
        List<Container> containers = RLPUtils.getRLPContainers(clazz);
        for (int i = 0; i < fields.size(); i++) {
            RLPElement el = element.get(i);
            Field f = fields.get(i);
            Container container = containers.get(i);

            RLPDecoder fieldDecoder = RLPUtils.getAnnotatedRLPDecoder(f);
            if (fieldDecoder != null) {
                try {
                    f.set(o, fieldDecoder.decode(el));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                continue;
            }

            try {
                f.set(o, decodeContainer(el, container, context));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return o;
    }

    // rlp primitives encoding/decoding
    public static byte[] encodeBoolean(boolean b) {
        return RLPItem.fromBoolean(b).getEncoded();
    }

    public static boolean decodeBoolean(byte[] encoded) {
        return RLPElement.fromEncoded(encoded).asBoolean();
    }

    public static byte[] encodeByte(byte b) {
        return RLPItem.fromByte(b).getEncoded();
    }

    public static byte[] encodeShort(short s) {
        return RLPItem.fromShort(s).getEncoded();
    }

    public static byte[] encodeInt(int n) {
        return RLPItem.fromInt(n).getEncoded();
    }

    public static byte[] encodeBigInteger(BigInteger bigInteger) {
        return RLPItem.fromBigInteger(bigInteger).getEncoded();
    }

    public static byte[] encodeString(String s) {
        return RLPItem.fromString(s).getEncoded();
    }

    public static int decodeInt(byte[] encoded) {
        return RLPElement.fromEncoded(encoded).asInt();
    }

    public static short decodeShort(byte[] encoded) {
        return RLPElement.fromEncoded(encoded).asShort();
    }

    public static long decodeLong(byte[] encoded) {
        return RLPElement.fromEncoded(encoded).asLong();
    }

    public static String decodeString(byte[] encoded) {
        return RLPElement.fromEncoded(encoded).asString();
    }

    public static byte[] encode(Object o, RLPContext context) {
        return RLPElement.readRLPTree(o, context).getEncoded();
    }

    public static byte[] encode(Object o) {
        return RLPElement.readRLPTree(o).getEncoded();
    }

    // rlp list encode
    public static byte[] encodeBytes(byte[] srcData) {
        // [0x80]
        if (srcData == null || srcData.length == 0) {
            return new byte[]{(byte) RLPConstants.OFFSET_SHORT_ITEM};
            // [0x00]
        }
        if (srcData.length == 1 && (srcData[0] & 0xFF) < RLPConstants.OFFSET_SHORT_ITEM) {
            return srcData;
            // [0x80, 0xb7], 0 - 55 bytes
        }
        if (srcData.length < RLPConstants.SIZE_THRESHOLD) {
            // length = 8X
            byte length = (byte) (RLPConstants.OFFSET_SHORT_ITEM + srcData.length);
            byte[] data = Arrays.copyOf(srcData, srcData.length + 1);
            System.arraycopy(data, 0, data, 1, srcData.length);
            data[0] = length;

            return data;
            // [0xb8, 0xbf], 56+ bytes
        }
        // length of length = BX
        // prefix = [BX, [length]]
        int tmpLength = srcData.length;
        byte lengthOfLength = 0;
        while (tmpLength != 0) {
            ++lengthOfLength;
            tmpLength = tmpLength >> 8;
        }

        // set length Of length at first byte
        byte[] data = new byte[1 + lengthOfLength + srcData.length];
        data[0] = (byte) (RLPConstants.OFFSET_LONG_ITEM + lengthOfLength);

        // copy length after first byte
        tmpLength = srcData.length;
        for (int i = lengthOfLength; i > 0; --i) {
            data[i] = (byte) (tmpLength & 0xFF);
            tmpLength = tmpLength >> 8;
        }

        // at last copy the number bytes after its length
        System.arraycopy(srcData, 0, data, 1 + lengthOfLength, srcData.length);

        return data;
    }

    public static byte[] encodeElements(@NonNull Collection<byte[]> elements) {
        int totalLength = 0;
        for (byte[] element1 : elements) {
            totalLength += element1.length;
        }

        byte[] data;
        int copyPos;
        if (totalLength < RLPConstants.SIZE_THRESHOLD) {

            data = new byte[1 + totalLength];
            data[0] = (byte) (RLPConstants.OFFSET_SHORT_LIST + totalLength);
            copyPos = 1;
        } else {
            // length of length = BX
            // prefix = [BX, [length]]
            int tmpLength = totalLength;
            byte byteNum = 0;
            while (tmpLength != 0) {
                ++byteNum;
                tmpLength = tmpLength >> 8;
            }
            tmpLength = totalLength;
            byte[] lenBytes = new byte[byteNum];
            for (int i = 0; i < byteNum; ++i) {
                lenBytes[byteNum - 1 - i] = (byte) ((tmpLength >> (8 * i)) & 0xFF);
            }
            // first byte = F7 + bytes.length
            data = new byte[1 + lenBytes.length + totalLength];
            data[0] = (byte) (RLPConstants.OFFSET_LONG_LIST + byteNum);
            System.arraycopy(lenBytes, 0, data, 1, lenBytes.length);

            copyPos = lenBytes.length + 1;
        }
        for (byte[] element : elements) {
            System.arraycopy(element, 0, data, copyPos, element.length);
            copyPos += element.length;
        }
        return data;
    }

    static RLPElement encodeCollection(Collection col, Comparator contentOrdering, RLPContext context) {
        Stream<Object> s = contentOrdering == null ? col.stream() : col.stream().sorted(contentOrdering);
        return new RLPList(s.map(x -> RLPElement.readRLPTree(x, context))
                .collect(Collectors.toList()));
    }

    static RLPElement encodeMap(Map m, Comparator keyOrdering, RLPContext context) {
        RLPList list = RLPList.createEmpty(m.size() * 2);
        Stream<Map.Entry> entires = m.entrySet().stream();
        if (keyOrdering != null) entires =
                entires.sorted((x, y) -> keyOrdering.compare(x.getKey(), y.getKey()));
        entires.forEach(x -> {
            list.add(RLPElement.readRLPTree(x.getKey(), context));
            list.add(RLPElement.readRLPTree(x.getValue(), context));
        });
        return list;
    }

    public static Object decodeContainer(byte[] encoded, Container container) {
        return decodeContainer(encoded, container, RLPContext.EMPTY);
    }

    public static Object decodeContainer(byte[] encoded, Container container, RLPContext context) {
        return decodeContainer(RLPElement.fromEncoded(encoded), container, context);
    }

    public static Object decodeContainer(RLPElement element, Container container) {
        return decodeContainer(element, container, RLPContext.EMPTY);
    }

    public static Object decodeContainer(RLPElement element, Container container, RLPContext context) {
        if (container == null) return element;
        switch (container.getType()) {
            case RAW:
                return decode(element, container.asRaw(), context);
            case COLLECTION: {
                CollectionContainer collectionContainer = container.asCollection();
                Collection res = (Collection) RLPUtils.newInstance(getDefaultImpl(collectionContainer.collectionType));
                if (element.isNull()) return res;
                for (int i = 0; i < element.size(); i++) {
                    res.add(decodeContainer(element.get(i), collectionContainer.contentType, context));
                }
                return res;
            }
            case MAP: {
                MapContainer mapContainer = container.asMap();
                Map res = (Map) RLPUtils.newInstance(getDefaultImpl(mapContainer.mapType));
                if (element.isNull()) return res;
                for (int i = 0; i < element.size(); i += 2) {
                    res.put(
                            decodeContainer(element.get(i), mapContainer.keyType, context),
                            decodeContainer(element.get(i + 1), mapContainer.valueType, context)
                    );
                }
                return res;
            }
        }
        throw new RuntimeException("unreachable");
    }

    private static Class getDefaultImpl(Class clazz) {
        if (clazz == Collection.class
                || clazz == List.class
        ) {
            return ArrayList.class;
        }
        if (clazz == Set.class) {
            return HashSet.class;
        }
        if (clazz == Queue.class) {
            return LinkedList.class;
        }
        if (clazz == Deque.class) {
            return ArrayDeque.class;
        }
        if (clazz == Map.class) {
            return HashMap.class;
        }
        if (clazz == ConcurrentMap.class) {
            return ConcurrentHashMap.class;
        }
        if (clazz.isInterface() || Modifier.isAbstract(clazz.getModifiers()))
            throw new RuntimeException("cannot new instance of " + clazz);
        return clazz;
    }
}
