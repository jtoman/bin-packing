import org.yaml.snakeyaml.Yaml
import java.io.File
import java.lang.IllegalStateException
import java.util.BitSet
import kotlin.math.max
import kotlin.math.pow

var numCompletions = 0

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

data class SearchParams(
    val c: Int,
    val sumS : Int,
    val lowerBound: Int,
    val S: List<BinItem>
) {
    val minSize : Int get() = S.last().sz
    val nElems : Int get() = S.size
}

class GlobalObjective(
    var currBest: List<List<BinItem>>,
) {
    val best: Int get() = currBest.size

    context(SearchParams)
    val W get() = ((best - 1) * c) - sumS
}

suspend fun SequenceScope<List<Int>>.feasible(
    params: SearchParams,
    assigned: BitSet,
    nextItem: Int,
    I: BitSet,
    excl: MutableSet<Int>,

    l : Int,
    u : Int,
    testAndYield: suspend SequenceScope<List<Int>>.(BitSet, Set<Int>) -> Unit,
) {
    var xInd = nextItem
    if(u == 0 || params.minSize > u) {
        if(l > 0) {
            return
        }
        testAndYield(I, excl)
        return
    }
    while(xInd < params.nElems && (assigned.get(xInd) || params.S[xInd].sz > u)) {
        xInd++
    }
    if(xInd >= params.nElems) {
        if(l > 0) {
            return
        }
        testAndYield(I, excl)
        return
    }
    val currBinSize = params.S[xInd].sz
    check(xInd in params.S.indices)
    // inclusion
    I.set(xInd)
    feasible(
        params, assigned, xInd + 1, I, excl, l - currBinSize, u - currBinSize, testAndYield
    )
    I.clear(xInd)

    excl.add(currBinSize)
    feasible(
        params, assigned, xInd + 1, I, excl, maxOf(l, currBinSize + 1), u, testAndYield
    )
    excl.remove(currBinSize)
}

val SearchParams.params get() = this

suspend fun SequenceScope<Int>.subsetSums(
    params: SearchParams,
    assigned: BitSet,
    i: Int,
    currSum: Int
) {
    val tgt = assigned.nextSetBit(i)
    if(tgt == -1) {
        if(currSum != 0) {
            yield(currSum)
        }
        return
    }
    subsetSums(
        params, assigned, tgt + 1, currSum
    )
    subsetSums(
        params, assigned, tgt + 1, params.S[tgt].sz + currSum
    )
}

fun <T> BitSet.mapSet(f: (Int) -> T) : List<T> {
    val toRet = mutableListOf<T>()
    var next = this.nextSetBit(0)
    while(next != -1) {
        toRet.add(f(next))
        next = this.nextSetBit(next + 1)
    }
    return toRet
}

context(SearchParams)
fun generateCompletions(
    assigned: BitSet,
    chosen: Int
) : Sequence<List<Int>> {
    val residue = c - S[chosen].sz
    var nextItemStart = -1
    for(i in chosen + 1 ..< nElems) {
        if(!assigned.get(i)) {
            nextItemStart = i
            break
        }
    }
    return sequence<List<Int>> {
        feasible(
            params = params,
            assigned = assigned,
            nextItem = nextItemStart,
            excl = mutableSetOf(),
            I = BitSet(),
            l = 0,
            u = residue
        ) { I, excl ->
            val subsetSums = sequence<Int> {
                subsetSums(params, I, 0, 0)
            }.toList()
            val t = I.mapSet {
                S[it].sz
            }.sum()
            if(excl.all { x ->
                subsetSums.all { s ->
                    (x - s > residue - t) || s > x
                }
            }) {
                this.yield(listOf(chosen) + I.mapSet {
                    it
                })
            }
        }
    }
}

context(SearchParams)
fun List<BinItem>.waste() = c - sumOf { it.sz }

context(SearchParams)
fun searchTree(
    g: GlobalObjective,

    searchStack: MutableList<List<BinItem>>,
    assigned: BitSet,
    currElem: Int,
    currWaste: Int
) : Boolean {
    if(currElem == nElems) {
        numCompletions++
        println("Found new solution: ${searchStack.size} vs ${g.best}")
        check(assigned.cardinality() == nElems)
        return if(searchStack.size < g.best) {
            g.currBest = searchStack.toList()
            // we are done
            g.best == lowerBound
        } else {
            false
        }
    }
    if(assigned.get(currElem)) {
        return searchTree(g, searchStack, assigned, currElem + 1, currWaste)
    }
    if(searchStack.size == g.best) {
        return false // have unassigned elements, can't beat our best assignment
    }
    for(complete in generateCompletions(assigned, currElem)) {
        val bin = complete.map {
            S[it]
        }
        val binWaste = bin.waste()
        if(currWaste + binWaste > g.W) {
            continue
        }
        for(ind in complete) {
            assigned.set(ind)
        }
        searchStack.add(bin)
        if(searchTree(g, searchStack, assigned, currElem + 1, currWaste + binWaste)) {
            return true
        }
        searchStack.removeLast()
        for(ind in complete) {
            assigned.clear(ind)
        }
    }
    return false
}

fun bfd(l: List<BinItem>, c: Int) : List<List<BinItem>> {
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
        it.elems
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

fun decimalPlaces(
    s: String
): Int {
    return decimalPlaces(s.toFloat())
}

fun decimalPlaces(
    s: Float
): Int {
    val x = s.toString().split(".")
    if(x.size == 1) {
        return 0
    }
    check(x.size == 2)
    return x[1].trimEnd('0').length
}

fun main(args: Array<String>) {
    val node = File(args[0]).reader().use {
        Yaml().load<List<Map<String, Any?>>>(it)
    }
    val rawNodes = mutableListOf<Pair<String, Float>>()
    for((i, n) in node.withIndex()) {
        if("name" !in n || n["name"] !is String) {
            throw IllegalStateException("Missing or malformed 'name' field in entry $i")
        }
        if("scale" !in n || (n["scale"] !is Number)) {
            throw IllegalStateException("Missing or malformed 'scale' field in entry $i")
        }
        val scale = (n["scale"] as Number).toFloat()
        rawNodes.add((n["name"] as String) to scale)
    }
    val binSize = args[1]
    val maxPrecision = max(rawNodes.maxOfOrNull {
        decimalPlaces(it.second)
    }!!, decimalPlaces(binSize))

    val scaleAmount = (10.0).pow(maxPrecision.toDouble()).toInt()
    println(scaleAmount)
    println(maxPrecision)

    val c = (binSize.toFloat() * scaleAmount).toInt()
    val bins = rawNodes.map {
        BinItem(
            it.first, (it.second * scaleAmount).toInt()
        )
    }.sortedByDescending { it.sz }

    val naive = bfd(bins, c)
    check(naive.flatten().toSet() == bins.toSet())
    val lowerBound = l2(bins, c)
    val params = SearchParams(
        c = c,
        S = bins,
        lowerBound = lowerBound,
        sumS = bins.sumOf { it.sz }
    )
    val obj = GlobalObjective(
        currBest = naive
    )
    val found = with(params) {
        searchTree(
            g = obj,
            assigned = BitSet(),
            currElem = 0,
            currWaste = 0,
            searchStack = mutableListOf()
        )
    }
    println("Found optimal: $found")
    println(obj.currBest.size)
    for(i in obj.currBest) {
        val w = with(params) {
            i.waste()
        }.toDouble() / scaleAmount
        println("Bucket: $i (waste: $w)")
    }
}