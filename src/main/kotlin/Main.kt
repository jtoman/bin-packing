import org.yaml.snakeyaml.Yaml
import java.io.File
import java.lang.IllegalStateException
import java.util.BitSet
import kotlin.math.max
import kotlin.math.pow
import kotlin.streams.toList

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

data class ProposedPacking(
    val waste: Int,
    val sz: Int,
    val membership: BitSet
)

data class SearchParams(
    val c: Int,
    val sumS : Int,
    val lowerBound: Int,
    val S: List<BinItem>,
    val enumerationStrategy: SubsetEnumerationStrategy,
    val iterationStrategy: SearchParams.(Sequence<ProposedPacking>) -> Sequence<ProposedPacking>
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

suspend fun SequenceScope<ProposedPacking>.feasible(
    params: SearchParams,
    assigned: BitSet,
    nextItem: Int,
    I: BitSet,
    excl: MutableSet<Int>,

    completionSize: Int,
    l : Int,
    u : Int,
    testAndYield: suspend SequenceScope<ProposedPacking>.(Int, BitSet, Set<Int>) -> Unit,
) {
    var xInd = nextItem
    if(u == 0 || params.minSize > u) {
        if(l > 0) {
            return
        }
        testAndYield(completionSize, I, excl)
        return
    }
    while(xInd < params.nElems && (assigned.get(xInd) || params.S[xInd].sz > u)) {
        xInd++
    }
    if(xInd >= params.nElems) {
        if(l > 0) {
            return
        }
        testAndYield(completionSize, I, excl)
        return
    }
    val currBinSize = params.S[xInd].sz
    check(xInd in params.S.indices)
    // inclusion
    I.set(xInd)
    feasible(
        params, assigned, xInd + 1, I, excl, completionSize + currBinSize, l - currBinSize, u - currBinSize, testAndYield
    )
    I.clear(xInd)

    excl.add(currBinSize)
    feasible(
        params, assigned, xInd + 1, I, excl, completionSize, maxOf(l, currBinSize + 1), u, testAndYield
    )
    excl.remove(currBinSize)
}

val SearchParams.params get() = this

suspend fun SequenceScope<Int>.subsetSums(
    params: SearchParams,
    assigned: BitSet,
    i: Int,
    currSum: Int,
    pred: (Int) -> Boolean
) {
    val tgt = assigned.nextSetBit(i)
    if(tgt == -1) {
        if(currSum != 0 && pred(currSum)) {
            yield(currSum)
        }
        return
    }
    subsetSums(
        params, assigned, tgt + 1, currSum, pred
    )
    subsetSums(
        params, assigned, tgt + 1, params.S[tgt].sz + currSum, pred
    )
}

suspend fun SequenceScope<Int>.subsetSums(
    params: SearchParams,
    assigned: BitSet,
    i: Int,
    currSum: Int
) {
    return subsetSums(params, assigned, i, currSum) { _ -> true }
}

suspend fun SequenceScope<Int>.subsetSums(
    params: SearchParams,
    assigned: BitSet,
    i: Int,
    currSum: Int,
    bound: Int
) {
    return subsetSums(params, assigned, i, currSum) { sum ->
        sum <= bound
    }
}

fun interface ExclusionDominationTest {
    fun test(x: Int): Boolean
}

interface SubsetEnumerationStrategy {
    context(SearchParams)
    fun prepare(
        I: BitSet,
        exclusionDiffLB: Int
    ): ExclusionDominationTest
}

object UpfrontEnumeration : SubsetEnumerationStrategy {
    context(SearchParams) override fun prepare(I: BitSet, exclusionDiffLB: Int): ExclusionDominationTest {
        val subsetSums = sequence<Int> {
            subsetSums(params, assigned = I, currSum = 0, i = 0)
        }.toList()
        return ExclusionDominationTest { x : Int ->
            subsetSums.all { s ->
                (x - s > exclusionDiffLB) || s > x
            }
        }
    }
}

object OnDemandEnumeration : SubsetEnumerationStrategy {
    context(SearchParams) override fun prepare(I: BitSet, exclusionDiffLB: Int): ExclusionDominationTest {
        return ExclusionDominationTest { x: Int ->
            sequence {
                subsetSums(params, i = 0, currSum = 0, assigned = I, bound = x)
            }.all { s ->
                x - s > exclusionDiffLB
            }
        }
    }
}

class SortedIteration(private val chunkSize: Int) : (SearchParams, Sequence<ProposedPacking>) -> Sequence<ProposedPacking> {
    override fun invoke(p1: SearchParams, p2: Sequence<ProposedPacking>): Sequence<ProposedPacking> {
        return sequence {
            for(buffer in p2.chunked(chunkSize)) {
                val sorted = buffer.sortedWith { a, b ->
                    when {
                        a.sz < b.sz -> 1
                        a.sz > b.sz -> -1
                        a.membership.size() != b.membership.size() -> {
                            a.membership.size() - b.membership.size()
                        }
                        else -> {
                            val lastElemA = a.membership.length() - 1
                            val lastElemB = b.membership.length() - 1
                            check(lastElemA >= 0 || lastElemB >= 0)
                            p1.S[lastElemA].sz - p1.S[lastElemB].sz
                        }
                    }
                }
                yieldAll(sorted)
            }
        }
    }

}

object EagerIteration : (SearchParams, Sequence<ProposedPacking>) -> Sequence<ProposedPacking> {
    override fun invoke(params: SearchParams, toRet: Sequence<ProposedPacking>): Sequence<ProposedPacking> {
        return toRet
    }
}

context(SearchParams)
fun generateCompletions(
    assigned: BitSet,
    chosen: Int,
    wasteFilter: (bucketWaste: Int) -> Boolean
) : Sequence<ProposedPacking> {
    val chosenSize = S[chosen].sz
    val residue = c - chosenSize
    var nextItemStart = -1
    for(i in chosen + 1 ..< nElems) {
        if(!assigned.get(i)) {
            nextItemStart = i
            break
        }
    }
    return sequence<ProposedPacking> {
        feasible(
            params = params,
            assigned = assigned,
            nextItem = nextItemStart,
            excl = mutableSetOf(),
            I = BitSet(),
            completionSize = 0,
            l = 0,
            u = residue
        ) feasibleTest@{ completionSize, I, excl ->
            val bucketSize = chosenSize + completionSize
            val bucketWaste = c - bucketSize
            if(!wasteFilter(bucketWaste)) {
                return@feasibleTest
            }
            val tester = enumerationStrategy.prepare(I, residue - completionSize)
            val failingExcl = excl.filterNot { x -> tester.test(x) }
            if(failingExcl.isEmpty()) {
                val toYield = I.clone() as BitSet
                toYield.set(chosen)
                yield(ProposedPacking(
                    sz = bucketSize,
                    membership = toYield,
                    waste = bucketWaste
                ))
            }
        }
    }
}

context(SearchParams)
fun List<BinItem>.waste() = c - sumOf { it.sz }

context(SearchParams)
fun BitSet.toBucket() : List<BinItem> = this.stream().mapToObj {
    S[it]
}.toList()

context(SearchParams)
fun searchTree(
    g: GlobalObjective,

    searchStack: MutableList<BitSet>,
    assigned: BitSet,
    currElem: Int,
    currWaste: Int
) : Boolean {
    if(currElem == nElems) {
        println("Found new solution: ${searchStack.size} vs ${g.best}")
        check(assigned.cardinality() == nElems)
        return if(searchStack.size < g.best) {
            g.currBest = searchStack.map { it.toBucket() }
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
    for(bin in params.iterationStrategy(generateCompletions(assigned, currElem) {
        bucketWaste -> currWaste + bucketWaste <= g.W
    })) {
        val binWaste = bin.waste
        assigned.or(bin.membership)
        searchStack.add(bin.membership)
        if(searchTree(g, searchStack, assigned, currElem + 1, currWaste + binWaste)) {
            return true
        }
        searchStack.removeLast()
        assigned.andNot(bin.membership)
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
        sumS = bins.sumOf { it.sz },
        iterationStrategy = SortedIteration(7),
        enumerationStrategy = UpfrontEnumeration
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