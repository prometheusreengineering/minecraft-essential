package studio.dreamys.prometheus.essential.ext

import java.util.Arrays
import java.util.stream.Stream

fun <T> Array<T>.stream(): Stream<T>
    = Arrays.stream(this)