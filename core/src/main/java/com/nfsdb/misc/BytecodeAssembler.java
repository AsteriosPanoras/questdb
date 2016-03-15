/*******************************************************************************
 *  _  _ ___ ___     _ _
 * | \| | __/ __| __| | |__
 * | .` | _|\__ \/ _` | '_ \
 * |_|\_|_| |___/\__,_|_.__/
 *
 * Copyright (c) 2014-2016. The NFSdb project and its contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package com.nfsdb.misc;

import com.nfsdb.ql.impl.map.RecordComparator;
import com.nfsdb.std.Mutable;
import com.sun.tools.javac.jvm.ByteCodes;
import sun.invoke.anon.AnonymousClassLoader;

import java.io.FileOutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class BytecodeAssembler implements Mutable {

    private static final int O_POOL_COUNT = 8;
    private ByteBuffer buf;
    private int poolCount;
    private int objectClassIndex;
    private int defaultConstructorNameIndex;
    private int defaultConstructorDescIndex;
    private int defaultConstructorMethodIndex;
    private int codeAttributeIndex;
    private int methodCut1;
    private int methodCut2;

    public BytecodeAssembler() {
        this.buf = ByteBuffer.allocate(4 * 1024).order(ByteOrder.BIG_ENDIAN);
        this.poolCount = 1;
    }

    public static void main(String[] args) throws UnsupportedEncodingException, IllegalAccessException, InstantiationException {
        AnonymousClassLoader l = AnonymousClassLoader.make(Unsafe.getUnsafe(), BytecodeAssembler.class);

        BytecodeAssembler asm = new BytecodeAssembler();
        asm.setupPool();
        int thisClassIndex = asm.poolClass(asm.poolUtf8("cmp"));
        int ifaceClassIndex = asm.poolClass(asm.poolUtf8("com/nfsdb/ql/impl/map/RecordComparator"));
        asm.finishPool();
        asm.defineClass(1, thisClassIndex);

        // interface count
        asm.putShort(1);
        asm.putShort(ifaceClassIndex);
        // field count
        asm.putShort(0);
        // method count
        asm.putShort(1);
        asm.defineDefaultConstructor();
        // class attribute count
        asm.putShort(0);

        byte b[] = new byte[asm.position()];
        System.arraycopy(asm.buf.array(), 0, b, 0, b.length);

        Class<RecordComparator> clazz = asm.loadClass(l);
        RecordComparator c = clazz.newInstance();
        c.setLeft(null);
//        System.out.println(clazz.newInstance() instanceof RecordComparator);
    }

    @Override
    public void clear() {
        this.buf.clear();
        this.poolCount = 1;
    }

    public BytecodeAssembler defineClass(int flags, int thisClassIndex) {
        // access flags
        putShort(flags);
        // this class index
        putShort(thisClassIndex);
        // super class
        putShort(objectClassIndex);
        return this;
    }

    public void defineDefaultConstructor() {
        // constructor method entry
        startMethod(1, defaultConstructorNameIndex, defaultConstructorDescIndex, 1, 1);
        // code
        put(ByteCodes.aload_0);
        put(ByteCodes.invokespecial);
        putShort(defaultConstructorMethodIndex);
        put(ByteCodes.return_);
        endMethodCode();
        // exceptions
        putShort(0);
        // attribute count
        putShort(0);
        endMethod();
    }

    public void defineField(int flags, int nameIndex, int typeIndex) {
        putShort(flags);
        putShort(nameIndex);
        putShort(typeIndex);
        // attribute count
        putShort(0);
    }

    public void dump(String path) {
        try (FileOutputStream fos = new FileOutputStream(path)) {
            int p = buf.position();
            int l = buf.limit();
            buf.flip();
            fos.getChannel().write(buf);
            buf.limit(l);
            buf.position(p);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public BytecodeAssembler endMethod() {
        putInt(methodCut1, position() - methodCut1 - 4);
        return this;
    }

    public BytecodeAssembler endMethodCode() {
        putInt(methodCut2, position() - methodCut2 - 4);
        return this;
    }

    public void finishPool() {
        putShort(O_POOL_COUNT, poolCount);
    }

    public byte get(int pos) {
        return buf.get(pos);
    }

    public void invokeInterface(int interfaceIndex) {
        put(ByteCodes.invokeinterface);
        putShort(interfaceIndex);
        put(0x02);
        put(0);
    }

    @SuppressWarnings("unchecked")
    public <T> Class<T> loadClass(AnonymousClassLoader l) {
        byte b[] = new byte[position()];
        System.arraycopy(buf.array(), 0, b, 0, b.length);
        return (Class<T>) l.loadClass(b);
    }

    public int poolClass(int classIndex) {
        put(0x07);
        putShort(classIndex);
        return poolCount++;
    }

    public int poolField(int classIndex, int nameAndTypeIndex) {
        return poolRef(0x09, classIndex, nameAndTypeIndex);
    }

    public int poolInterfaceMethod(int classIndex, int nameAndTypeIndex) {
        return poolRef(0x0B, classIndex, nameAndTypeIndex);
    }

    public int poolMethod(int classIndex, int nameAndTypeIndex) {
        return poolRef(0x0A, classIndex, nameAndTypeIndex);
    }

    public int poolNameAndType(int nameIndex, int typeIndex) {
        return poolRef(0x0C, nameIndex, typeIndex);
    }

    public int poolUtf8(CharSequence cs) {
        put(0x01);
        int n;
        putShort(n = cs.length());
        for (int i = 0; i < n; i++) {
            put(cs.charAt(i));
        }
        return this.poolCount++;
    }

    public int position() {
        return buf.position();
    }

    public void put(int b) {
        buf.put((byte) b);
    }

    public void putConstant(int v) {
        if (v > -1 && v < 6) {
            put(ByteCodes.iconst_0 + v);
        } else if (v < 0) {
            put(ByteCodes.sipush);
            putShort(v);
        } else if (v < 128) {
            put(ByteCodes.bipush);
            put(v);
        } else {
            put(ByteCodes.sipush);
            putShort(v);
        }
    }

    public void putInt(int v) {
        buf.putInt(v);
    }

    public void putInt(int pos, int v) {
        buf.putInt(pos, v);
    }

    public void putShort(int v) {
        putShort((short) v);
    }

    public void putShort(short v) {
        buf.putShort(v);
    }

    public void putShort(int pos, int v) {
        buf.putShort(pos, (short) v);
    }

    public void putStackMapAppendInt(int stackMapTableIndex, int position) {
        putShort(stackMapTableIndex);
        int lenPos = position();
        // length - we will come back here
        putInt(0);
        // number of entries
        putShort(1);
        // frame type APPEND
        put(0xFC);
        // offset delta - points at branch target
        putShort(position);
        // type: int
        put(0x01);
        // fix attribute length
        putInt(lenPos, position() - lenPos - 4);
    }

    public void setupPool() {
        // magic
        putInt(0xCAFEBABE);
        // version
        putInt(0x33);
        // skip pool count, write later when we know the value
        putShort(0);

        // add standard stuff
        objectClassIndex = poolClass(poolUtf8("java/lang/Object"));
        defaultConstructorMethodIndex = poolMethod(objectClassIndex, poolNameAndType(
                defaultConstructorNameIndex = poolUtf8("<init>"),
                defaultConstructorDescIndex = poolUtf8("()V"))
        );
        codeAttributeIndex = poolUtf8("Code");
    }

    public BytecodeAssembler startMethod(int flags, int nameIndex, int descriptorIndex, int maxStack, int maxLocal) {
        // access flags
        putShort(flags);
        // name index
        putShort(nameIndex);
        // descriptor index
        putShort(descriptorIndex);
        // attribute count
        putShort(1);

        // code
        putShort(codeAttributeIndex);

        // come back to this later
        this.methodCut1 = position();
        // attribute len
        putInt(0);
        // max stack
        putShort(maxStack);
        // max locals
        putShort(maxLocal);

        // code len
        this.methodCut2 = position();
        putInt(0);

        return this;
    }

    private int poolRef(int op, int name, int type) {
        put(op);
        putShort(name);
        putShort(type);
        return poolCount++;
    }
}
