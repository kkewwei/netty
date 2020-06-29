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

/**
 * Description of algorithm for PageRun/PoolSubpage allocation from PoolChunk
 *
 * Notation: The following terms are important to understand the code
 * > page  - a page is the smallest unit of memory chunk that can be allocated
 * > chunk - a chunk is a collection of pages
 * > in this code chunkSize = 2^{maxOrder} * pageSize
 *
 * To begin we allocate a byte array of size = chunkSize
 * Whenever a ByteBuf of given size needs to be created we search for the first position
 * in the byte array that has enough empty space to accommodate the requested size and
 * return a (long) handle that encodes this offset information, (this memory segment is then
 * marked as reserved so it is always used by exactly one ByteBuf and no more)
 *
 * For simplicity all sizes are normalized according to PoolArena#normalizeCapacity method
 * This ensures that when we request for memory segments of size >= pageSize the normalizedCapacity
 * equals the next nearest power of 2
 *
 * To search for the first offset in chunk that has at least requested size available we construct a
 * complete balanced binary tree and store it in an array (just like heaps) - memoryMap
 *
 * The tree looks like this (the size of each node being mentioned in the parenthesis)
 *
 * depth=0        1 node (chunkSize)
 * depth=1        2 nodes (chunkSize/2)
 * ..
 * ..
 * depth=d        2^d nodes (chunkSize/2^d)
 * ..
 * depth=maxOrder 2^maxOrder nodes (chunkSize/2^{maxOrder} = pageSize)
 *
 * depth=maxOrder is the last level and the leafs consist of pages
 *
 * With this tree available searching in chunkArray translates like this:
 * To allocate a memory segment of size chunkSize/2^k we search for the first node (from left) at height k
 * which is unused
 *
 * Algorithm:
 * ----------
 * Encode the tree in memoryMap with the notation
 *   memoryMap[id] = x => in the subtree rooted at id, the first node that is free to be allocated
 *   is at depth x (counted from depth=0) i.e., at depths [depth_of_id, x), there is no node that is free
 *
 *  As we allocate & free nodes, we update values stored in memoryMap so that the property is maintained
 *
 * Initialization -
 *   In the beginning we construct the memoryMap array by storing the depth of a node at each node
 *     i.e., memoryMap[id] = depth_of_id
 *
 * Observations:
 * -------------
 * 1) memoryMap[id] = depth_of_id  => it is free / unallocated
 * 2) memoryMap[id] > depth_of_id  => at least one of its child nodes is allocated, so we cannot allocate it, but
 *                                    some of its children can still be allocated based on their availability
 * 3) memoryMap[id] = maxOrder + 1 => the node is fully allocated & thus none of its children can be allocated, it
 *                                    is thus marked as unusable
 *
 * Algorithm: [allocateNode(d) => we want to find the first node (from left) at height h that can be allocated]
 * ----------
 * 1) start at root (i.e., depth = 0 or id = 1)
 * 2) if memoryMap[1] > d => cannot be allocated from this chunk
 * 3) if left node value <= h; we can allocate from left subtree so move to left and repeat until found
 * 4) else try in right subtree
 *
 * Algorithm: [allocateRun(size)]
 * ----------
 * 1) Compute d = log_2(chunkSize/size)
 * 2) Return allocateNode(d)
 *
 * Algorithm: [allocateSubpage(size)]
 * ----------
 * 1) use allocateNode(maxOrder) to find an empty (i.e., unused) leaf (i.e., page)
 * 2) use this handle to construct the PoolSubpage object or if it already exists just call init(normCapacity)
 *    note that this PoolSubpage object is added to subpagesPool in the PoolArena when we init() it
 *
 * Note:
 * -----
 * In the implementation for improving cache coherence,
 * we store 2 pieces of information (i.e, 2 byte vals) as a short value in memoryMap
 *
 * memoryMap[id]= (depth_of_id, x)
 * where as per convention defined above
 * the second value (i.e, x) indicates that the first node which is free to be allocated is at depth x (from root)
 */   //从第0层开始算起，是16M（一个点）， 下标从1开始算，第11层最开始下标为2048，8K。那么第0层8k*2048 = 16M
final class PoolChunk<T> implements PoolChunkMetric {
   // // PoolChunk会涉及到具体的内存,泛型T表示byte[](堆内存)、或java.nio.ByteBuffer(堆外内存)
    private static final int INTEGER_SIZE_MINUS_ONE = Integer.SIZE - 1;

    final PoolArena<T> arena;//PoolArena arena;该PoolChunk所属的PoolArena。
    final T memory;   // // 具体用来表示内存；byte[]或java.nio.ByteBuffer。
    final boolean unpooled;  //// 是否是可重用的，unpooled=false表示可重用
    final int offset;
    //4096
    private final byte[] memoryMap;  //PoolChunk的物理视图是连续的PoolSubpage,用PoolSubpage保持，而memoryMap是所有PoolSubpage的逻辑映射，映射为一颗平衡二叉数，用来标记每一个PoolSubpage是否被分配。下文会更进一步说明
    private final byte[] depthMap;    //而depthMap中保存的值表示各个id对应的深度，是个固定值，初始化后不再变更。
    private final PoolSubpage<T>[] subpages;// 该PoolChunk所包含的PoolSupage。也就是PoolChunk连续的可用内存。
    /** Used to determine if the requested capacity is equal to or greater than pageSize. */
    private final int subpageOverflowMask;//用来判断申请的内存是否超过pageSize的掩码，等于  ~(pageSize-1)
    private final int pageSize;  //每个PoolSubpage的大小，默认为8192个字节（8K)
    private final int pageShifts;  //pageSize 2的 pageShifts幂
    private final int maxOrder; // 平衡二叉树的深度，一个PoolChunk包含 2的 maxOrder幂(  1 << maxOrder ) 个PoolSubpage。 // maxOrder最大值为14
    private final int chunkSize;   //PoolChunk的总内存大小,chunkSize =   (1<<maxOrder) * pageSize。
    private final int log2ChunkSize;   // chunkSize 是  2的 log2ChunkSize幂，如果chunkSize = 2 的 10次幂,那么 log2ChunkSize=10
    private final int maxSubpageAllocs; // PoolChunk由maxSubpageAllocs个PoolSubpage组成。
    /** Used to mark memory as unusable */
    private final byte unusable; //标记为已被分配的值，该值为 maxOrder + 1=12, 当memoryMap[id] = unusable时，则表示id节点已被分配

    private int freeBytes;   //当前PoolChunk空闲的内存 = 16M。

    PoolChunkList<T> parent;  //一个PoolChunk分配后，会根据使用率挂在一个PoolChunkList中，在(PoolArena的PoolChunkList上)
    PoolChunk<T> prev;// PoolChunk本身设计为一个链表结构。
    PoolChunk<T> next;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    PoolChunk(PoolArena<T> arena, T memory, int pageSize, int maxOrder, int pageShifts, int chunkSize, int offset) {
        unpooled = false;
        this.arena = arena;//DirectArea
        this.memory = memory;  //DirectBytebuffer
        this.pageSize = pageSize;
        this.pageShifts = pageShifts;  //8k
        this.maxOrder = maxOrder;//8k-16m
        this.chunkSize = chunkSize;  //16M
        this.offset = offset;
        unusable = (byte) (maxOrder + 1);  //12
        log2ChunkSize = log2(chunkSize); //16m
        subpageOverflowMask = ~(pageSize - 1);  //-8192
        freeBytes = chunkSize;//16m
         //该树树顶内存大小8M，
        assert maxOrder < 30 : "maxOrder should be < 30, but is: " + maxOrder;
        maxSubpageAllocs = 1 << maxOrder; //2048,，最多2048个page

        // Generate the memory map.
        memoryMap = new byte[maxSubpageAllocs << 1]; //4096
        depthMap = new byte[memoryMap.length];
        int memoryMapIndex = 1;
        for (int d = 0; d <= maxOrder; ++ d) { // move down the tree one level at a time
            int depth = 1 << d;
            for (int p = 0; p < depth; ++ p) {
                // in each level traverse left to right and set value to the depth of subtree
                memoryMap[memoryMapIndex] = (byte) d;
                depthMap[memoryMapIndex] = (byte) d;
                memoryMapIndex ++;
            }
        }

        subpages = newSubpageArray(maxSubpageAllocs); //poolChunk中维护了一个大小为2048的poolSubpage数组，分别对应二叉树中2048个叶子节点
    }

    /** Creates a special chunk that is not pooled. */
    PoolChunk(PoolArena<T> arena, T memory, int size, int offset) {
        unpooled = true;
        this.arena = arena;
        this.memory = memory;
        this.offset = offset;
        memoryMap = null;
        depthMap = null;
        subpages = null;
        subpageOverflowMask = 0;
        pageSize = 0;
        pageShifts = 0;
        maxOrder = 0;
        unusable = (byte) (maxOrder + 1);
        chunkSize = size;
        log2ChunkSize = log2(chunkSize);
        maxSubpageAllocs = 0;
    }

    @SuppressWarnings("unchecked")
    private PoolSubpage<T>[] newSubpageArray(int size) {
        return new PoolSubpage[size];
    }

    @Override
    public int usage() {
        final int freeBytes;
        synchronized (arena) {
            freeBytes = this.freeBytes;
        }
        return usage(freeBytes);
    }

    private int usage(int freeBytes) {
        if (freeBytes == 0) {
            return 100;
        }

        int freePercentage = (int) (freeBytes * 100L / chunkSize);
        if (freePercentage == 0) {
            return 99;
        }
        return 100 - freePercentage;
    }

    long allocate(int normCapacity) {
        if ((normCapacity & subpageOverflowMask) != 0) { // >= pageSize    ~(pageSize-1)
            return allocateRun(normCapacity);  //// 大于等于pageSize时返回的是可分配normCapacity的节点的id
        } else {  //分配小于8k
            return allocateSubpage(normCapacity); //（small）返回是第几个long,long第几位
        }
    }

    /**
     * Update method used by allocate
     * This is triggered only when a successor is allocated and all its predecessors
     * need to update their state
     * The minimal depth at which subtree rooted at id has some free space
     *
     * @param id id
     */
    private void updateParentsAlloc(int id) {
        while (id > 1) {  //开始更新父类节点的值
            int parentId = id >>> 1;
            byte val1 = value(id);
            byte val2 = value(id ^ 1);  //获取相邻节点的值
            byte val = val1 < val2 ? val1 : val2;
            setValue(parentId, val);
            id = parentId;
        }
    }

    /**
     * Update method used by free
     * This needs to handle the special case when both children are completely free
     * in which case parent be directly allocated on request of size = child-size * 2
     *
     * @param id id
     */
    private void updateParentsFree(int id) {
        int logChild = depth(id) + 1;
        while (id > 1) {
            int parentId = id >>> 1;
            byte val1 = value(id);
            byte val2 = value(id ^ 1);
            logChild -= 1; // in first iteration equals log, subsequently reduce 1 from logChild as we traverse up

            if (val1 == logChild && val2 == logChild) {
                setValue(parentId, (byte) (logChild - 1));
            } else {
                byte val = val1 < val2 ? val1 : val2;
                setValue(parentId, val);
            }

            id = parentId;
        }
    }

    /**
     * Algorithm to allocate an index in memoryMap when we query for a free node
     * at depth d
     *
     * @param d depth
     * @return index in memoryMap
     *///仅仅返回的是下标
    private int allocateNode(int d) { //层
        int id = 1;//第一个，是下标
        int initial = - (1 << d); // has last d bits = 0 and rest all = 1
        byte val = value(id);
        if (val > d) { // unusable  //若第一层的深度不够，那么该chunkend不够分配
            return -1;
        }  //(id & initial) == 0 ---> id < 2^d  ==是为了控制最小匹配的，比如现在寻找第8层的，那么小于8层（坐标）的取值的话，不用考虑，一定还在下面（因为判断了该chunk里面一定可以找到符合的））
        while (val < d || (id & initial) == 0) { // id & initial == 1 << d for all ids at depth d, for < d it is 0
            id <<= 1;
            val = value(id);
            if (val > d) {//如果当前层数>需要的层数，
                id ^= 1;  //切到兄弟分支上面去
                val = value(id);
            }
        }
        byte value = value(id);
        assert value == d && (id & initial) == 1 << d : String.format("val = %d, id & initial = %d, d = %d",
                value, id & initial, d);
        setValue(id, unusable); // mark as unusable
        updateParentsAlloc(id);
        return id; //仅仅返回的是下标
    }

    /**
     * Allocate a run of pages (>=1)
     *
     * @param normCapacity normalized capacity
     * @return index in memoryMap
     */
    private long allocateRun(int normCapacity) {//64k
        int d = maxOrder - (log2(normCapacity) - pageShifts);  //算出当前阶层
        int id = allocateNode(d);
        if (id < 0) {
            return id;
        }
        freeBytes -= runLength(id);
        return id;
    }

    /**
     * Create/ initialize a new PoolSubpage of normCapacity
     * Any PoolSubpage created/ initialized here is added to subpage pool in the PoolArena that owns this PoolChunk
     *
     * @param normCapacity normalized capacity
     * @return index in memoryMap
     */
    private long allocateSubpage(int normCapacity) {
        // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
        // This is need as we may add it back and so alter the linked-list structure.
        PoolSubpage<T> head = arena.findSubpagePoolHead(normCapacity);  //// 找到arena中对应阶级的subpage头节点，不存数据的头结点
        synchronized (head) {
            int d = maxOrder; // subpages are only be allocated from pages i.e., leaves   subpage只能从叶子节点开始找起
            int id = allocateNode(d); //只在叶子节点找到一个为8k的节点，肯定可以找到一个节点
            if (id < 0) {
                return id;
            }

            final PoolSubpage<T>[] subpages = this.subpages;
            final int pageSize = this.pageSize;

            freeBytes -= pageSize;//（就是一个16M的空间）

            int subpageIdx = subpageIdx(id);  //第几个PoolSubpage（叶子节点）
            PoolSubpage<T> subpage = subpages[subpageIdx];
            if (subpage == null) {  //说明这个PoolSubpagte还没有分配出去
                subpage = new PoolSubpage<T>(head, this, id, runOffset(id), pageSize, normCapacity);
                subpages[subpageIdx] = subpage;
            } else {   //该档已经有值了, 还不清楚这个是什么意思，为啥还需要初始化一次？
                subpage.init(head, normCapacity);
            }
            return subpage.allocate();
        }
    }

    /**
     * Free a subpage or a run of pages
     * When a subpage is freed from PoolSubpage, it might be added back to subpage pool of the owning PoolArena
     * If the subpage pool in PoolArena has at least one other PoolSubpage of given elemSize, we can
     * completely free the owning Page so it is available for subsequent allocations
     *
     * @param handle handle to free
     */
    void free(long handle) {
        int memoryMapIdx = memoryMapIdx(handle);
        int bitmapIdx = bitmapIdx(handle);

        if (bitmapIdx != 0) { // free a subpage
            PoolSubpage<T> subpage = subpages[subpageIdx(memoryMapIdx)];
            assert subpage != null && subpage.doNotDestroy;

            // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
            // This is need as we may add it back and so alter the linked-list structure.
            PoolSubpage<T> head = arena.findSubpagePoolHead(subpage.elemSize);
            synchronized (head) {
                if (subpage.free(head, bitmapIdx & 0x3FFFFFFF)) {
                    return;
                }
            }
        }
        freeBytes += runLength(memoryMapIdx);
        setValue(memoryMapIdx, depth(memoryMapIdx));
        updateParentsFree(memoryMapIdx);
    }
    //可以根据handle判断是哪个tiny，small,或者normal
    void initBuf(PooledByteBuf<T> buf, long handle, int reqCapacity) {
        int memoryMapIdx = memoryMapIdx(handle);  //把handler低32位和高32位分开了
        int bitmapIdx = bitmapIdx(handle); //占用了多少个int  ，偏移量小于32位，肯定是>8k的分配，因为最多4095个pageSize。再向右移动32位，直接变为0
        if (bitmapIdx == 0) { // // 到这里表示分配的是>=pageSize的数据
            byte val = value(memoryMapIdx);  //检查需要的是否已经标记为12了
            assert val == unusable : String.valueOf(val);
            buf.init(this, handle, runOffset(memoryMapIdx) + offset, reqCapacity, runLength(memoryMapIdx),
                     arena.parent.threadCache());
        } else {//// 到这里表示分配的是<pageSize的数据
            initBufWithSubpage(buf, handle, bitmapIdx, reqCapacity);
        }
    }

    void initBufWithSubpage(PooledByteBuf<T> buf, long handle, int reqCapacity) {
        initBufWithSubpage(buf, handle, bitmapIdx(handle), reqCapacity);
    }
    //handle， 高32放着一个PoolSubpage里面哪段的哪个，低32位放着哪个叶子节点，bitmapIdx是高32位
    private void initBufWithSubpage(PooledByteBuf<T> buf, long handle, int bitmapIdx, int reqCapacity) {
        assert bitmapIdx != 0;

        int memoryMapIdx = memoryMapIdx(handle); //在哪个叶子节点里面放着

        PoolSubpage<T> subpage = subpages[subpageIdx(memoryMapIdx)];  //获取到那个叶子节点
        assert subpage.doNotDestroy;
        assert reqCapacity <= subpage.elemSize;  //真正需要的内存大小

        buf.init(  //buf =PooledUnsafeDirectByteBuf
            this, handle,
            runOffset(memoryMapIdx) + (bitmapIdx & 0x3FFFFFFF) * subpage.elemSize + offset,
                reqCapacity, subpage.elemSize, arena.parent.threadCache());
    }

    private byte value(int id) {
        return memoryMap[id];
    }

    private void setValue(int id, byte val) {
        memoryMap[id] = val;
    }

    private byte depth(int id) {
        return depthMap[id];
    }

    private static int log2(int val) {
        // compute the (0-based, with lsb = 0) position of highest set bit i.e, log2
        return INTEGER_SIZE_MINUS_ONE - Integer.numberOfLeadingZeros(val);
    }
    //坐标为id，应该的大小
    private int runLength(int id) {  //比如第0层，2^24,第11层  2^13。 2^13 = 2^(24-11)
        // represents the size in #bytes supported by node 'id' in the tree
        return 1 << log2ChunkSize - depth(id);  //先加减，后左移
    }

    private int runOffset(int id) {
        // represents the 0-based offset in #bytes from start of the byte-array chunk
        int shift = id ^ 1 << depth(id);  //先左移，后异或，也是得到的叶子节点内，第几个
        return shift * runLength(id);
    }
    //获取的是叶子节点的第几个
    private int subpageIdx(int memoryMapIdx) {
        return memoryMapIdx ^ maxSubpageAllocs; // remove highest set bit, to get offset
    }  //最多2048个节点，memoryMapIdx表示在哪个叶子节点，获取的是叶子节点的第几个

    private static int memoryMapIdx(long handle) { //低32位
        return (int) handle;
    }
    ////高32放着一个PoolSubpage里面哪段的哪个，低32位放着哪个叶子节点
    private static int bitmapIdx(long handle) { //高32位
        return (int) (handle >>> Integer.SIZE);
    }

    @Override
    public int chunkSize() {
        return chunkSize;
    }

    @Override
    public int freeBytes() {
        synchronized (arena) {
            return freeBytes;
        }
    }

    @Override
    public String toString() {
        final int freeBytes;
        synchronized (arena) {
            freeBytes = this.freeBytes;
        }

        return new StringBuilder()
                .append("Chunk(")
                .append(Integer.toHexString(System.identityHashCode(this)))
                .append(": ")
                .append(usage(freeBytes))
                .append("%, ")
                .append(chunkSize - freeBytes)
                .append('/')
                .append(chunkSize)
                .append(')')
                .toString();
    }

    void destroy() {
        arena.destroyChunk(this);
    }
}
