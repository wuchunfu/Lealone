/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package com.lealone.db.index.standard;

import java.nio.ByteBuffer;

import com.lealone.db.DataBuffer;
import com.lealone.db.DataHandler;
import com.lealone.db.result.Row;
import com.lealone.db.value.CompareMode;
import com.lealone.db.value.Value;
import com.lealone.db.value.ValueArray;
import com.lealone.db.value.ValueLong;

public class IndexKeyType extends ValueDataType {

    private final StandardSecondaryIndex index;

    public IndexKeyType(DataHandler handler, CompareMode compareMode, int[] sortTypes,
            StandardSecondaryIndex index) {
        super(handler, compareMode, sortTypes);
        this.index = index;
    }

    @Override
    public int compare(Object a, Object b) {
        if (a == b) {
            return 0;
        }
        if (a == null) {
            return -1;
        } else if (b == null) {
            return 1;
        }
        Value[] ax = ((IndexKey) a).columns;
        Value[] bx = ((IndexKey) b).columns;
        return compareValues(ax, bx);
    }

    @Override
    public int getMemory(Object obj) {
        IndexKey k = (IndexKey) obj;
        int memory = 4;
        if (k == null)
            return memory;
        Value[] columns = k.columns;
        for (int i = 0, len = columns.length; i < len; i++) {
            Value c = columns[i];
            if (c == null)
                memory += 4;
            else
                memory += c.getMemory();
        }
        return memory;
    }

    @Override
    public Object read(ByteBuffer buff) {
        ValueArray a = (ValueArray) DataBuffer.readValue(buff);
        return new IndexKey(a.getList());
    }

    @Override
    public void write(DataBuffer buff, Object obj) {
        IndexKey k = (IndexKey) obj;
        buff.writeValue(ValueArray.get(k.columns));
    }

    @Override
    public Object convertToIndexKey(Object key, Object value) {
        Row row = new Row(((VersionedValue) value).columns);
        row.setKey(((ValueLong) key).getLong());
        return index.convertToKey(row);
    }
}
