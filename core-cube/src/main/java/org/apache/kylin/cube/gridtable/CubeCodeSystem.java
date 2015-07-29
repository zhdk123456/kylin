package org.apache.kylin.cube.gridtable;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import org.apache.kylin.common.util.ByteArray;
import org.apache.kylin.common.util.Bytes;
import org.apache.kylin.common.util.BytesUtil;
import org.apache.kylin.common.util.ImmutableBitSet;
import org.apache.kylin.cube.kv.RowConstants;
import org.apache.kylin.dict.Dictionary;
import org.apache.kylin.gridtable.GTInfo;
import org.apache.kylin.gridtable.IGTCodeSystem;
import org.apache.kylin.gridtable.IGTComparator;
import org.apache.kylin.metadata.measure.MeasureAggregator;
import org.apache.kylin.metadata.measure.serializer.DataTypeSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Created by shaoshi on 3/23/15.
 * This implementation uses Dictionary to encode and decode the table; If a column doesn't have dictionary, will check
 * its data type to serialize/deserialize it;
 */
@SuppressWarnings({ "rawtypes", "unchecked" })
public class CubeCodeSystem implements IGTCodeSystem {
    private static final Logger logger = LoggerFactory.getLogger(CubeCodeSystem.class);

    // ============================================================================

    private GTInfo info;
    private Map<Integer, Dictionary> dictionaryMap; // column index ==> dictionary of column
    private Map<Integer, Integer> fixLenMap; // column index ==> fixed length of column
    private Map<Integer, Integer> dependentMetricsMap;
    private IGTComparator comparator;
    private DataTypeSerializer[] serializers;

    public CubeCodeSystem(Map<Integer, Dictionary> dictionaryMap) {
        this(dictionaryMap, Collections.<Integer, Integer>emptyMap(), Collections.<Integer, Integer>emptyMap());
    }
            
    public CubeCodeSystem(Map<Integer, Dictionary> dictionaryMap, Map<Integer, Integer> fixLenMap, Map<Integer, Integer> dependentMetricsMap) {
        this.dictionaryMap = dictionaryMap;
        this.fixLenMap = fixLenMap;
        this.dependentMetricsMap = dependentMetricsMap;
    }

    @Override
    public void init(GTInfo info) {
        this.info = info;

        serializers = new DataTypeSerializer[info.getColumnCount()];
        for (int i = 0; i < info.getColumnCount(); i++) {
            // dimension with dictionary
            if (dictionaryMap.get(i) != null) {
                serializers[i] = new DictionarySerializer(dictionaryMap.get(i));
            }
            // dimension of fixed length
            else if (fixLenMap.get(i) != null) {
                serializers[i] = new FixLenSerializer(fixLenMap.get(i));
            }
            // metrics
            else {
                serializers[i] = DataTypeSerializer.create(info.getColumnType(i));
            }
        }

        this.comparator = new IGTComparator() {
            @Override
            public boolean isNull(ByteArray code) {
                // all 0xff is null
                byte[] array = code.array();
                for (int i = 0, j = code.offset(), n = code.length(); i < n; i++, j++) {
                    if (array[j] != Dictionary.NULL)
                        return false;
                }
                return true;
            }

            @Override
            public int compare(ByteArray code1, ByteArray code2) {
                return code1.compareTo(code2);
            }
        };
    }

    @Override
    public IGTComparator getComparator() {
        return comparator;
    }

    @Override
    public int codeLength(int col, ByteBuffer buf) {
        return serializers[col].peekLength(buf);
    }

    @Override
    public int maxCodeLength(int col) {
        return serializers[col].maxLength();
    }

    @Override
    public void encodeColumnValue(int col, Object value, ByteBuffer buf) {
        encodeColumnValue(col, value, 0, buf);
    }

    @Override
    public void encodeColumnValue(int col, Object value, int roundingFlag, ByteBuffer buf) {
        // this is a bit too complicated, but encoding only happens once at build time, so it is OK
        DataTypeSerializer serializer = serializers[col];
        try {
            if (serializer instanceof DictionarySerializer) {
                ((DictionarySerializer) serializer).serializeWithRounding(value, roundingFlag, buf);
            } else {
                serializer.serialize(value, buf);
            }
        } catch (ClassCastException ex) {
            // try convert string into a correct object type
            try {
                if (value instanceof String) {
                    Object converted = serializer.valueOf((String) value);
                    if ((converted instanceof String) == false) {
                        encodeColumnValue(col, converted, roundingFlag, buf);
                        return;
                    }
                }
            } catch (Throwable e) {
                logger.error("Fail to encode value '" + value + "'", e);
            }
            throw ex;
        }
    }

    @Override
    public Object decodeColumnValue(int col, ByteBuffer buf) {
       return serializers[col].deserialize(buf);
    }

    @Override
    public MeasureAggregator<?>[] newMetricsAggregators(ImmutableBitSet columns, String[] aggrFunctions) {
        assert columns.trueBitCount() == aggrFunctions.length;
        
        MeasureAggregator<?>[] result = new MeasureAggregator[aggrFunctions.length];
        for (int i = 0; i < result.length; i++) {
            int col = columns.trueBitAt(i);
            result[i] = MeasureAggregator.create(aggrFunctions[i], info.getColumnType(col).toString());
        }
        
        // deal with holistic distinct count
        if (dependentMetricsMap != null) {
            for (Integer child : dependentMetricsMap.keySet()) {
                if (columns.get(child)) {
                    Integer parent = dependentMetricsMap.get(child);
                    if (columns.get(parent) == false)
                        throw new IllegalStateException();
                    
                    int childIdx = columns.trueBitIndexOf(child);
                    int parentIdx = columns.trueBitIndexOf(parent);
                    result[childIdx].setDependentAggregator(result[parentIdx]);
                }
            }
        }
        
        return result;
    }

    static class DictionarySerializer extends DataTypeSerializer {
        private Dictionary dictionary;

        DictionarySerializer(Dictionary dictionary) {
            this.dictionary = dictionary;
        }

        public void serializeWithRounding(Object value, int roundingFlag, ByteBuffer buf) {
            int id = dictionary.getIdFromValue(value, roundingFlag);
            BytesUtil.writeUnsigned(id, dictionary.getSizeOfId(), buf);
        }

        @Override
        public void serialize(Object value, ByteBuffer buf) {
            int id = dictionary.getIdFromValue(value);
            BytesUtil.writeUnsigned(id, dictionary.getSizeOfId(), buf);
        }

        @Override
        public Object deserialize(ByteBuffer in) {
            int id = BytesUtil.readUnsigned(in, dictionary.getSizeOfId());
            return dictionary.getValueFromId(id);
        }

        @Override
        public int peekLength(ByteBuffer in) {
            return dictionary.getSizeOfId();
        }

        @Override
        public int maxLength() {
            return dictionary.getSizeOfId();
        }

        @Override
        public Object valueOf(byte[] value) {
            throw new UnsupportedOperationException();
        }
    }

    static class FixLenSerializer extends DataTypeSerializer {

        // be thread-safe and avoid repeated obj creation
        private ThreadLocal<byte[]> current = new ThreadLocal<byte[]>();

        private int fixLen;

        FixLenSerializer(int fixLen) {
            this.fixLen = fixLen;
        }
        
        private byte[] currentBuf() {
            byte[] buf = current.get();
            if (buf == null) {
                buf = new byte[fixLen];
                current.set(buf);
            }
            return buf;
        }

        @Override
        public void serialize(Object value, ByteBuffer out) {
            byte[] buf = currentBuf();
            if (value == null) {
                Arrays.fill(buf, Dictionary.NULL);
                out.put(buf);
            } else {
                byte[] bytes = Bytes.toBytes(value.toString());
                if (bytes.length > fixLen) {
                    throw new IllegalStateException("Expect at most " + fixLen + " bytes, but got " + bytes.length + ", value string: " + value.toString());
                }
                out.put(bytes);
                for (int i = bytes.length; i < fixLen; i++) {
                    out.put(RowConstants.ROWKEY_PLACE_HOLDER_BYTE);
                }
            }
        }

        @Override
        public Object deserialize(ByteBuffer in) {
            byte[] buf = currentBuf();
            in.get(buf);

            int tail = fixLen;
            while (tail > 0 && (buf[tail - 1] == RowConstants.ROWKEY_PLACE_HOLDER_BYTE || buf[tail - 1] == Dictionary.NULL)) {
                tail--;
            }
            
            if (tail == 0) {
                return buf[0] == Dictionary.NULL ? null : "";
            }
            
            return Bytes.toString(buf, 0, tail);
        }

        @Override
        public int peekLength(ByteBuffer in) {
            return fixLen;
        }

        @Override
        public int maxLength() {
            return fixLen;
        }

        @Override
        public Object valueOf(byte[] value) {
            try {
                return new String(value, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                // does not happen
                throw new RuntimeException(e);
            }
        }

    }

}