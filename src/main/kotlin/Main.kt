import java.util.BitSet

data class BinItem(
    val label: String,
    val sz: Int
)

tailrec fun bruteForceSearch(
    assignmentArray: Array<Int>,
    sourceList: List<BinItem>,
    assignmentStack: Array<Int>,
    cur: Int
) : Boolean {
    if(cur == -1) {
        return false
    }
    if(cur == sourceList.size) {
        return true
    }
    val currBin = sourceList[cur]
    val searchStart : Int
    if(assignmentStack[cur] == -1) {
        searchStart = 0
    } else {
        // pop current assignment
        assignmentArray[assignmentStack[cur]] += currBin.sz
        searchStart = assignmentStack[cur] + 1
        assignmentStack[cur] = -1
    }
    for(targetBin in searchStart ..< assignmentArray.size) {
        if(assignmentArray[targetBin] >= currBin.sz) {
            assignmentStack[cur] = targetBin
            assignmentArray[targetBin] -= currBin.sz
            return bruteForceSearch(
                assignmentArray, sourceList, assignmentStack, cur + 1
            )
        }
    }
    // no bin left, pop to parent
    return bruteForceSearch(
        assignmentArray, sourceList, assignmentStack, cur - 1
    )
}

class Bin {
    private val elemsInner = mutableListOf<BinItem>()
    val elems : List<BinItem> get() = elemsInner
    var sz : Int = 0
        private set

    fun add(it: BinItem) {
        this.sz += it.sz
        var toAddInd = -1
        for(i in elems.withIndex()) {
            if(i.value.sz < it.sz) {
                toAddInd = i.index
                break
            }
        }
        if(toAddInd == -1) {
            elemsInner.add(it)
        } else {
            elemsInner.add(toAddInd, it)
        }
    }
    val empty get() = sz == 0

    infix fun dominates(other: Bin) : Boolean {
        if(other.empty) {
            return true
        }
        if(this.empty) {
            return false
        }
        if(this.elems[0].sz < other.elems[0].sz) {
            return false
        }
        return bruteForceSearch(
            assignmentArray = Array(elems.size) {
                elems[it].sz
            },
            sourceList = other.elems,
            assignmentStack = Array(other.elems.size) {
                -1
            },
            cur = 0
        )
    }
}

fun bfd(l: List<BinItem>, c: Int) : List<Set<BinItem>> {
    val bins = mutableListOf<Bin>()

    outer@for(it in l) {
        val (_, sz) = it
        var cand: Bin? = null
        for(b in bins) {
            if(b.sz + sz <= c) {
                if(cand == null || b.sz > cand.sz) {
                    cand = b
                }
            }
        }
        if(cand == null) {
            val newBin = Bin()
            bins.add(newBin)
            cand = newBin
        }
        cand.add(it)
    }
    return bins.map {
        it.elems.toSet()
    }
}

class SkipListNode<T>(
    val value: T,
    var internal: InternalNode<T>
) {
    class InternalNode<T>(
        val lower: InternalNode<T>?,
        var nxt: SkipListNode<T>?
    )
}

class SkipList<T>(
    var head: SkipListNode<T>?,
    val comparator: Comparator<T>
) {
    fun insert(x: T) {
        if(head == null) {
            head = SkipListNode(
                value = x,
                internal = SkipListNode.InternalNode(null, null)
            )
            return
        }
    }

    tailrec private fun insertInternal(
        node: SkipListNode<T>,
        payload: T,
        cont: (SkipListNode<T>) -> Unit
    ) {
        val cmp = comparator.compare(node.value, payload)
        when {
            cmp < 0 -> {
                // this node is too small, keep going
                var internalStack = node.internal
                if(internalStack.nxt == null) {
                    if(internalStack.lower == null) {
                        // lowest level, and nothing left? this is the last element of the list

                    }
                }
            }
        }
    }
}

fun l1(l: List<BinItem>, c: Int)  : Int {
    val totalSize = l.sumOf {
        it.sz
    }
    val div = totalSize / c
    return if(div % c == 0) {
        c
    } else {
        c + 1
    }
}

fun l2(l: List<BinItem>, c: Int) : Int {
    var currWaste = 0
    val removed = BitSet()
    var it = 0
    var residual = 0
    while(removed.cardinality() < l.size) {
        if(removed.get(it)) {
            it++
            continue
        }
        removed.set(it)
        val r = c - l[it].sz
        var sum = residual
        residual = 0
        for(lt in it + 1..<l.size) {
            if(removed.get(lt) || l[lt].sz > r) {
                continue
            }
            removed.set(lt)
            sum += l[lt].sz
        }
        if(sum <= r) {
            currWaste += r - sum
        } else {
            residual = sum - r
        }
    }
    val sigma = l.sumOf {
        it.sz
    } + currWaste
    val div = sigma / c
    return if(sigma % c == 0) {
        div
    } else {
        div + 1
    }
}

fun main(args: Array<String>) {
    println("Hello World!")

    // Try adding program arguments via Run/Debug configuration.
    // Learn more about running applications: https://www.jetbrains.com/help/idea/running-applications.html.
    println("Program arguments: ${args.joinToString()}")
}