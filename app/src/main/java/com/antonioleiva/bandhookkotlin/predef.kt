package com.antonioleiva.bandhookkotlin

import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.functional.bind
import nl.komponents.kovenant.task
import nl.komponents.kovenant.then
import org.funktionale.collections.tail
import org.funktionale.either.Disjunction
import org.funktionale.option.Option
import org.funktionale.utils.identity

/**
 * A Result is a deferred promise containing either a controlled error and successful value or an unknown exception.
 * The containing Disjunction is right biased on A
 */

class Result<E, A>(private val value: Promise<Disjunction<E, A>, Exception>) {

    /**
     * map over the right successful case
     */
    fun <B> map(fa: (A) -> B): Result<E, B> =
            Result(value.then { it ->
                it.map { fa(it) }
            })

    /**
     * map over the left exceptional case
     */
    fun <EE> recover(r: Result<E, A>, fa: (E) -> EE): Result<EE, A> =
            Result(value.then { it ->
                it.swap().map { fa(it) }.swap()
            })

    /**
     * map over the left exceptional case
     */
    fun swap(): Result<A, E> = Result(value.then { it.swap() })


    /**
     * flatMap over the left exceptional case
     */
    fun <EE> recoverWith(fa: (E) -> Result<EE, A>): Result<EE, A> =
            swap().flatMap { fa(it).swap() }.swap()


    /**
     * Monadic bind allows sequential chains of promises
     */
    fun <B> flatMap(fa: (A) -> Result<E, B>): Result<E, B> =
            Result(value.bind {
                it.fold(fl = { l -> raiseError<E, B>(l).value }, fr = { r -> fa(r).value })
            })

    /**
     * Side effects
     */
    fun onComplete(
            onSuccess: (A) -> Unit,
            onError: (E) -> Unit,
            onUnhandledException: (Exception) -> Unit): Unit {
        value.success { it.fold(onError, onSuccess) } fail onUnhandledException
    }

    /**
     * Combine the results of r1 with r2 given both promises are successful
     * Since promises may have already started this operation is non-deterministic as there is
     * no guarantees as to which one finishes first but results are nonetheless delivered ordered
     */
    fun <B> zip(r2: Result<E, B>): Result<E, Pair<A, B>> =
            flatMap { a -> r2.map { b -> Pair(a, b) } }

    fun <B, AA> zipWith(r2: Result<E, B>, f: (A, B) -> AA): Result<E, AA> =
            flatMap { a -> r2.map { b -> f(a, b) } }

    companion object Factory {

        fun <E, A> asyncOf(f: () -> Disjunction<E, A>): Result<E, A> =
                Result(task {
                    f()
                } bind { fa ->
                    fromDisjunction(fa).value
                } fail { e ->
                    raiseUnknownError<Nothing, A>(e).value
                })

        /**
         * Lift any value to the monadic context of a Result
         */
        fun <E, A> pure(a: A): Result<E, A> =
                fromDisjunction(Disjunction.right(a))

        /**
         * Lift any value to the monadic context of a Result
         */
        fun <E, A> fromDisjunction(fa: Disjunction<E, A>): Result<E, A> =
                Result(Promise.ofSuccess(fa))

        /**
         * Raise an error placing it in the left of the contained disjunction on an already completed promise
         */
        fun <E, A> raiseError(e: E): Result<E, A> =
                Result(Promise.ofSuccess(Disjunction.left(e)))

        /**
         * Raise an unknown error placing it in the failed case on an already completed promise
         */
        fun <E, A> raiseUnknownError(e: Exception): Result<E, A> =
                Result(Promise.ofFail(e))


        /**
         * Given a result and a function in the Promise context, applies the
         * function to the result returning a transformed result. Delegates to zip which is non-deterministic
         */
        fun <E, A, B> ap(ff: Result<E, (A) -> B>, fa: Result<E, A>): Result<E, B> =
                fa.zip(ff).map { it.second(it.first) }

        fun <E, A, B> firstSuccessIn(fa: List<B>,
                                     acc: Result<E, A>,
                                     f: (B) -> Result<E, A>): Result<E, A> =
                if (fa.isEmpty()) acc
                else {
                    val current = fa[0]
                    val result = f(current)
                    result.recoverWith {
                        firstSuccessIn(fa.tail(), result, f)
                    }
                }

        fun <E, A, B> traverse(results: List<A>, f: (A) -> Result<E, B>): Result<E, List<B>> =
                results.fold(pure(emptyList()), { r1, r2 ->
                    r1.zipWith(f(r2), { l, a -> l + a })
                })

        fun <E, A> sequence(results: List<Result<E, A>>): Result<E, List<A>> =
                traverse(results, identity())


        fun <E, A, B> fold(results: List<Result<E, A>>, zero: B, f: (B, A) -> B): Result<E, B> =
                foldNext(results.iterator(), zero, f)

        fun <B, E, A : B> reduce(results: NonEmptyList<Result<E, A>>, f: (B, A) -> B): Result<E, B> {
            val iterator = results.all.iterator()
            return iterator.next().flatMap { a -> foldNext(iterator, a, f) }
        }

        private fun <E, A, B> foldNext(iterator: Iterator<Result<E, A>>, zero: B, f: (B, A) -> B): Result<E, B> =
                if (!iterator.hasNext()) pure<E, B>(zero)
                else iterator.next().flatMap { value -> foldNext(iterator, f(zero, value), f) }


    }

}

fun <E, A> A.result(a: A): Result<E, A> {
    return Result.pure(a)
}

fun <E, A> E.raiseError(e: E): Result<E, A> {
    return Result.raiseError(e)
}

fun <E, A> Exception.raiseAsUnknownError(e: Exception): Result<E, A> {
    return Result.raiseUnknownError(e)
}

fun <A, B> Option<A>.zip(that: Option<B>): Option<Pair<A, B>> {
    return this.flatMap { a -> that.map { b -> Pair(a, b) } }
}

fun <L> L.left(): Disjunction<L, Nothing> {
    return Disjunction.Left<L, Nothing>(this)
}

fun <R> R.right(): Disjunction<Nothing, R> {
    return Disjunction.Right<Nothing, R>(this)
}

interface NonEmptyCollection<out A> : Collection<A> {
    val head: A
    val tail: Collection<A>
}

abstract class AbstractNonEmptyCollection<out A>(
        override val head: A,
        override val tail: Collection<A>) : AbstractCollection<A>(), NonEmptyCollection<A> {

    @Suppress("LeakingThis")
    override val size: Int = 1 + tail.size

    override fun contains(element: @UnsafeVariance A): Boolean {
        return (head == element).or(tail.contains(element));
    }

    override fun isEmpty(): Boolean = false

}

class NonEmptyList<out A>(
        override val head: A,
        override val tail: List<A>) : AbstractNonEmptyCollection<A>(head, tail) {

    val all: List<A> = listOf(head) + tail

    inline fun <reified B> map(f: (A) -> B): NonEmptyList<B> =
            NonEmptyList(f(head), tail.map(f))

    inline fun <reified B> flatMap(f: (A) -> List<B>): NonEmptyList<B> =
            unsafeFromList(all.flatMap(f))

    override fun iterator(): Iterator<A> = all.iterator()

    companion object Factory {
        inline fun <reified A> of(h: A, vararg t: A): NonEmptyList<A> = NonEmptyList(h, t.asList())
        inline fun <reified A> unsafeFromList(l: List<A>): NonEmptyList<A> = NonEmptyList(l[0], l.tail())
    }

}
