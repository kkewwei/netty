/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.buffer;

final class PoolSubpage<T> implements PoolSubpageMetric {

    final PoolChunk<T> chunk;
    private final int memoryMapIdx;   // 当前page在chunk中的id
    private final int runOffset;  // 当前page在chunk.memory的偏移量
    private final int pageSize;
    private final long[] bitmap;//通过对每一个二进制位的标记来修改一段内存的占用状态

    PoolSubpage<T> prev;
    PoolSubpage<T> next;

    boolean doNotDestroy;   //Remove this subpage from the pool if there are other subpages left in the pool.
    int elemSize; // 该page切分后每一段的大小
    private int maxNumElems;// 该page包含的段数量
    private int bitmapLength;
    private int nextAvail; // 下一个可用的位置
    private int numAvail; // 可用的段数量

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    /** Special constructor that creates a linked list head */
    PoolSubpage(int pageSize) {  //仅仅构建头的使用用
        chunk = null;
        memoryMapIdx = -1;
        runOffset = -1;
        elemSize = -1;
        this.pageSize = pageSize;
        bitmap = null;
    }
    //那一级别的SubpAGE
    PoolSubpage(PoolSubpage<T> head, PoolChunk<T> chunk, int memoryMapIdx, int runOffset, int pageSize, int elemSize) {
        this.chunk = chunk;
        this.memoryMapIdx = memoryMapIdx;  //找到哪个叶子节点来进行subPage
        this.runOffset = runOffset;
        this.pageSize = pageSize; //最多只需要8个long,而且还是elemSize=16的时候，当elemSize>16的时候，bitmap为8的并用不完
        bitmap = new long[pageSize >>> 10]; //最小单位内存长度为16，一个page有 pageSize/16 = 512个单位，一个long长度为64，需要512/64=8个long就可全部描述所有page
        init(head, elemSize);
    }
    //init根据当前需要分配的内存大小，确定需要多少个bitmap元素
    void init(PoolSubpage<T> head, int elemSize) {   ///elemSize代表此次申请的大小，比如申请64byte，那么这个page被分成了8k/64=2^7=128个
        doNotDestroy = true;
        this.elemSize = elemSize;
        if (elemSize != 0) {
            maxNumElems = numAvail = pageSize / elemSize; //被分成了128份64大小的内存
            nextAvail = 0;
            bitmapLength = maxNumElems >>> 6;  //6代表着long长度 = 2
            if ((maxNumElems & 63) != 0) {//低6位
                bitmapLength ++;
            }

            for (int i = 0; i < bitmapLength; i ++) {
                bitmap[i] = 0;
            }
        }
        addToPool(head);
    }

    /**
     * Returns the bitmap index of the subpage allocation.
     */
    long allocate() { //找到一位
        if (elemSize == 0) {
            return toHandle(0);
        }

        if (numAvail == 0 || !doNotDestroy) {
            return -1;
        }

        final int bitmapIdx = getNextAvail(); //64进制
        int q = bitmapIdx >>> 6;//第几位long
        int r = bitmapIdx & 63; //这个long第几位
        assert (bitmap[q] >>> r & 1) == 0;
        bitmap[q] |= 1L << r;  //将相关位置置为1

        if (-- numAvail == 0) { //这个page没有再能够提供的bit位
            removeFromPool(); //从可分配链中去掉
        }

        return toHandle(bitmapIdx);
    }

    /**
     * @return {@code true} if this subpage is in use.
     *         {@code false} if this subpage is not used by its chunk and thus it's OK to be released.
     */
    boolean free(PoolSubpage<T> head, int bitmapIdx) {
        if (elemSize == 0) {
            return true;
        }
        int q = bitmapIdx >>> 6;
        int r = bitmapIdx & 63;
        assert (bitmap[q] >>> r & 1) != 0;
        bitmap[q] ^= 1L << r;

        setNextAvail(bitmapIdx);

        if (numAvail ++ == 0) { //目前没有可利用的，若释放后有的话，将该PoolSubpage放入该队列
            addToPool(head);
            return true;
        }

        if (numAvail != maxNumElems) { //目前还有可用的
            return true;
        } else {
            // Subpage not in use (numAvail == maxNumElems)
            if (prev == next) { //剩余的唯一一个，那就不将与page之前的映射解除了，也没必要
                // Do not remove if this subpage is the only one left in the pool.
                return true;
            }

            // Remove this subpage from the pool if there are other subpages left in the pool.
            doNotDestroy = false;
            removeFromPool();
            return false;
        }
    }
    //采用的是头插法
    private void addToPool(PoolSubpage<T> head) {
        assert prev == null && next == null;
        prev = head;
        next = head.next;
        next.prev = this;
        head.next = this;
    }

    private void removeFromPool() {
        assert prev != null && next != null;
        prev.next = next;
        next.prev = prev;
        next = null;
        prev = null;
    }

    private void setNextAvail(int bitmapIdx) {
        nextAvail = bitmapIdx;
    }
    //从subpage中找到某一个16的小块可用
    private int getNextAvail() {
        int nextAvail = this.nextAvail;
        if (nextAvail >= 0) {   // 初始第0个可用的，然后nextAvail置为-1,让自己去找(当别人释放的时候，会指明哪个可用。用完之后，找一下就得重头找)
            this.nextAvail = -1;
            return nextAvail;
        }
        return findNextAvail();
    }

    private int findNextAvail() {
        final long[] bitmap = this.bitmap; //每个page 8k, 最小16byte，需要8个long表示
        final int bitmapLength = this.bitmapLength;
        for (int i = 0; i < bitmapLength; i ++) {
            long bits = bitmap[i]; //其中某个
            if (~bits != 0) { //bits全部为说明未全部占用，选择下一个，某一个未占用，那么~bit!=0成立，从bit 64位找出这位
                return findNextAvail0(i, bits);
            }
        }
        return -1;
    }

    private int findNextAvail0(int i, long bits) {
        final int maxNumElems = this.maxNumElems; //包含的段个数
        final int baseVal = i << 6; //64进制，第2位，最大数也只是8*64 + 64

        for (int j = 0; j < 64; j ++) {
            if ((bits & 1) == 0) { //bits哪位为0，局说明哪位可用
                int val = baseVal | j; //第4个long+9  i<<6 + j
                if (val < maxNumElems) { //不能大于总段数
                    return val;
                } else {
                    break;
                }
            }
            bits >>>= 1;  //一位位找，直到找到某位为0
        }
        return -1;
    }
    //高32放着一个PoolSubpage里面哪段的哪个，低32位放着哪个叶子节点，若subpage节点，高位一定有值
    private long toHandle(int bitmapIdx) { //这样的话，相当于一种简单的编码，之后，可以根据这个编码解码相应的值
        return 0x4000000000000000L | (long) bitmapIdx << 32 | memoryMapIdx;  //高32放着一个PoolSubpage里面哪段的哪个，低32位放着哪个叶子节点
    }  //bitmapIdx一定是>0的数，这样的话，PoolSubpage一定可以返回一个>long的类型。从而和>8k的ik区分开

    @Override
    public String toString() {
        final boolean doNotDestroy;
        final int maxNumElems;
        final int numAvail;
        final int elemSize;
        synchronized (chunk.arena) {
            if (!this.doNotDestroy) {
                doNotDestroy = false;
                // Not used for creating the String.
                maxNumElems = numAvail = elemSize = -1;
            } else {
                doNotDestroy = true;
                maxNumElems = this.maxNumElems;
                numAvail = this.numAvail;
                elemSize = this.elemSize;
            }
        }

        if (!doNotDestroy) {
            return "(" + memoryMapIdx + ": not in use)";
        }

        return "(" + memoryMapIdx + ": " + (maxNumElems - numAvail) + '/' + maxNumElems +
                ", offset: " + runOffset + ", length: " + pageSize + ", elemSize: " + elemSize + ')';
    }

    @Override
    public int maxNumElements() {
        synchronized (chunk.arena) {
            return maxNumElems;
        }
    }

    @Override
    public int numAvailable() {
        synchronized (chunk.arena) {
            return numAvail;
        }
    }

    @Override
    public int elementSize() {
        synchronized (chunk.arena) {
            return elemSize;
        }
    }

    @Override
    public int pageSize() {
        return pageSize;
    }

    void destroy() {
        if (chunk != null) {
            chunk.destroy();
        }
    }
}
