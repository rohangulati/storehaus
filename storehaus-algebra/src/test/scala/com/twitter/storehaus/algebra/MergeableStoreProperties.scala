/*
 * Copyright 2013 Twitter Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.twitter.storehaus.algebra

import com.twitter.algebird.{MapAlgebra, Monoid, Semigroup}
import com.twitter.bijection.Injection
import com.twitter.storehaus._
import com.twitter.util.{Await, Duration, Future}
import org.scalacheck.{Arbitrary, Prop, Properties}
import org.scalacheck.Prop._
import scala.collection.breakOut

object MergeableStoreProperties extends Properties("MergeableStore") {
  def rightContainsLeft[K, V: Equiv](l: Map[K, V], r: Map[K, V]): Boolean =
    l.forall(pair => r.get(pair._1).map { Equiv[V].equiv(_, pair._2) }.getOrElse(true))

  // The optional equiv included in scalacheck doesn't pull in an
  // implicit Equiv[T].
  implicit def optEquiv[T: Equiv]: Equiv[Option[T]] =
    Equiv.fromFunction { (opt1, opt2) =>
      (opt1, opt2) match {
        case (Some(l), Some(r)) => Equiv[T].equiv(l, r)
        case (None, None) => true
        case (Some(_), None) => false
        case (None, Some(_)) => false
      }
    }

  implicit val timer = new com.twitter.util.ScheduledThreadPoolTimer(makeDaemons = true)

  implicit def mapEquiv[K, V: Monoid: Equiv]: Equiv[Map[K, V]] = {
    Equiv.fromFunction { (m1, m2) =>
      val cleanM1 = MapAlgebra.removeZeros(m1)
      val cleanM2 = MapAlgebra.removeZeros(m2)
      rightContainsLeft(cleanM1, cleanM2) && rightContainsLeft(cleanM2, cleanM1)
    }
  }

  // Adds a bunch of items and removes them and sees if they are absent
  def baseTest[K: Arbitrary, V: Arbitrary: Monoid: Equiv](store: MergeableStore[K, V])(put:
      (MergeableStore[K, V], List[(K, V)]) => Map[K, (Future[Option[V]], V)]): Prop =
    forAll { examples: List[(K, V)] =>
      val inputMap = MapAlgebra.sumByKey(examples).mapValues { Monoid.nonZeroOption(_) }
      val preResult = Await.result(FutureOps.mapCollect(store.multiGet(inputMap.keySet)))
      val expectedResult = Monoid.plus(inputMap, preResult)
        .mapValues { _.flatMap { Monoid.nonZeroOption(_) } }

      val res = put(store, examples) // Mutate the store
      // from the results, we can get the final value:
      val finalMerged = Await.result(FutureOps.mapCollect(
        res.mapValues { case (fopt, v) =>
          fopt.map { opt => opt.map { Monoid.plus(_, v) }.getOrElse(v) }
        }
      )).mapValues(Monoid.nonZeroOption(_))

      Equiv[Map[K, Option[V]]].equiv(
        expectedResult,
        Await.result(FutureOps.mapCollect(store.multiGet(expectedResult.keySet)))
      ) && Equiv[Map[K, Option[V]]].equiv(expectedResult, finalMerged)
    }.label("baseTest")

  def newStore[K, V: Semigroup]: MergeableStore[K, V] =
    MergeableStore.fromStore(new JMapStore[K, V])

  def newSparseStore[K, V: Monoid]: MergeableStore[K, V] =
    MergeableStore.fromStoreEmptyIsZero(new JMapStore[K, V])

  def newConvertedStore[K, V1, V2](
      implicit inj: Injection[V2, V1], monoid: Monoid[V2]): MergeableStore[K, V2] = {
    val store = new JMapStore[K, V1]
    val cstore = new com.twitter.storehaus.ConvertedStore[K, K, V1, V2](store)(identity[K])
    MergeableStore.fromStore(cstore)
  }

  def singleMergeableStoreTest[K: Arbitrary, V: Arbitrary: Monoid: Equiv](
      store: MergeableStore[K, V]): Prop =
    baseTest(store) { (s, pairs) =>
      pairs.map { case kv@(k, v) => k -> ((s.merge(kv), v)) }(breakOut)
    }

  def multiMergeableStoreTest[K: Arbitrary, V: Arbitrary: Monoid: Equiv](
      store: MergeableStore[K, V]): Prop =
    baseTest(store) { (s, pairs) =>
      val presummed = MapAlgebra.sumByKey(pairs)
      s.multiMerge(presummed)
        .map { case (k, fv) => (k, (fv, presummed(k))) }
    }

  def mergeableStoreTest[K: Arbitrary, V: Arbitrary: Monoid: Equiv](
      store: MergeableStore[K, V]): Prop =
    singleMergeableStoreTest(store).label("single") &&
      multiMergeableStoreTest(store).label("multi")

  property("MergeableStore from JMapStore obeys the mergeable store laws") =
    mergeableStoreTest(newStore[Int, Map[Int, Int]])

  property("MergeableStore from ConcurrentHashMapMergeableStore[Byte, Int] " +
      "obeys the mergeable store laws") =
    mergeableStoreTest(ConcurrentHashMapMergeableStore[Byte, Int]())

  property("MergeableStore - sparse - from JMapStore obeys the mergeable store laws") =
    mergeableStoreTest(newSparseStore[Int, Map[Int, Int]])

  property("MergeableStore from Converted JMapStore obeys the mergeable store laws") =
    mergeableStoreTest(newConvertedStore[Int, String, Int])

  property("Converted MergeableStore obeys the mergeable store laws") = {
    // We are using a weird monoid on Int here:
    import com.twitter.algebird.bijection.AlgebirdBijections._
    import com.twitter.bijection.Conversion.asMethod
    implicit val monoid: Monoid[Int] = implicitly[Monoid[(Short, Short)]].as[Monoid[Int]]

    mergeableStoreTest {
      new ConvertedMergeableStore[Int, Int, (Short, Short), Int](
        newStore[Int, (Short, Short)])(identity[Int])
    }
  }

  sealed trait Tree[+T] {
    def sumOption[U>:T](implicit sg: Semigroup[U]): Option[U]
    def map[U](fn: T => U): Tree[U]
    def forall[U>:T](pred: T => Boolean): Boolean =
      map(pred).sumOption(Semigroup.from[Boolean] { _ && _ }).getOrElse(true)
  }
  case object EmptyTree extends Tree[Nothing] {
    def sumOption[U>:Nothing](implicit sg: Semigroup[U]): Option[U] = None
    def map[U](fn: Nothing => U): Tree[Nothing] = EmptyTree
  }
  case class Leaf[+T](get: T) extends Tree[T] {
    def sumOption[U>:T](implicit sg: Semigroup[U]): Option[U] = Some(get)
    def map[U](fn: T => U): Leaf[U] = Leaf(fn(get))
  }
  case class Branch[T](left: Tree[T], right: Tree[T]) extends Tree[T] {
    def sumOption[U>:T](implicit sg: Semigroup[U]): Option[U] =
      (left.sumOption[U], right.sumOption[U]) match {
        case (Some(l), Some(r)) => Some(Semigroup.plus(l, r))
        case (sl@Some(l), _) => sl
        case (_, sr@Some(r)) => sr
        case _ => None
      }
    def map[U](fn: T => U): Branch[U] = Branch(left.map(fn), right.map(fn))
  }
  def toRandTree[T](s: Seq[T])(implicit rng: java.util.Random): Tree[T] =
    s.size match {
      case 0 => EmptyTree
      case 1 => Leaf(s.head)
      case sz =>
        val cut = rng.nextInt(sz)
        val left = toRandTree(s.slice(0, cut))
        val right = toRandTree(s.slice(cut, sz))
        Branch(left, right)
    }

  property("PromiseLink correctly sets intermediates") = forAll { (ins: List[Int]) =>
    implicit val rng = new java.util.Random
    val starts = ins.map { i => (i, PromiseLink(i)) }
    toRandTree(starts) match {
      case Branch(l, r) =>
        (
          for {
            ls <- l.sumOption
            rs <- r.sumOption
            // Now complete the computation:
            _ = Semigroup.plus(ls, rs) // link the two futures
            lr = starts.head._2(Some(0))
            rightBefore = Await.result(rs._2.promise).get
            leftSum = ls._1
          } yield leftSum == rightBefore // previous value is correct
        ).getOrElse(true)
      case _ => true
    }
  }

  // Returns the specified get/put results
  def mockGetPutStore[K, V](getResult: Future[Option[V]], putResult: Future[Unit] = Future.Unit): Store[K, V] =
    new MockGetPutByKeyStore[K, V](_ => getResult, _ => putResult)

  def mockGetPutByKeyStore[K, V](getResult: K => Future[Option[V]], putResult: K => Future[Unit]): Store[K, V] =
    new MockGetPutByKeyStore[K, V](getResult, putResult)

  // Returns the specified get/put results
  class MockGetPutByKeyStore[K, V](getResult: K => Future[Option[V]], putResult: K => Future[Unit]) extends Store[K, V] {
    override def get(k: K): Future[Option[V]] = getResult(k)
    override def put(kv: (K, Option[V])): Future[Unit] = putResult(kv._1)
  }

  property("multiMergeFromMultiSet handles store failures correctly") = {
    val intSg = implicitly[Semigroup[Int]]
    val storeException = new RuntimeException("Failure in store")
    val storeExceptionFut = Future.exception[Option[Int]](storeException)

    forAll { (ins: Map[Int, Int]) =>
      val result = MergeableStore.multiMergeFromMultiSet(
        mockGetPutStore[Int, Int](storeExceptionFut), ins)(intSg)
      result.values.forall { v =>
        Await.result(v.liftToTry).throwable == storeException
      }
    }
  }

  property("multiMergeFromMultiSet handles put failures correctly") = {
    val intSg = implicitly[Semigroup[Int]]
    val storeException = new RuntimeException("Failure in store")
    val getResultFut: Future[Option[Int]] = Future.value(None)
    val putExceptionFut = Future.exception[Unit](storeException)

    forAll { (ins: Map[Int, Int]) =>
      val result = MergeableStore.multiMergeFromMultiSet(
        mockGetPutStore[Int, Int](getResultFut, putExceptionFut), ins)(intSg)
      result.values.forall { v =>
        Await.result(v.liftToTry).throwable == storeException
      }
    }
  }

  def posMod(n: Int, d: Int): Int = ((n % d) + d) %d

  property("multiMergeFromMultiSet handles partial failures correctly") = {
    val intSg = implicitly[Semigroup[Int]]
    val storeException = new RuntimeException("Failure in store")
    val getExceptionFut = Future.exception[Option[Int]](storeException)
    val putExceptionFut = Future.exception[Unit](storeException)

    // Strategy: All keys divisible by 3 fail on get, with remainder 1 fail on put, rest should succeed
    def part1(k: Int): Boolean = posMod(k, 3) == 0
    def part2(k: Int): Boolean = posMod(k, 3) == 1
    def part3(k: Int): Boolean = posMod(k, 3) == 2

    val getResultFut: Int => Future[Option[Int]] = k => if (part1(k)) getExceptionFut else Future.value(Some(k))
    val putResultFut: Int => Future[Unit] = k => if (part2(k)) putExceptionFut else Future.Unit

    forAll { (ins: Map[Int, Int]) =>
      val result = MergeableStore.multiMergeFromMultiSet(
        mockGetPutByKeyStore[Int, Int](getResultFut, putResultFut), ins)(intSg)
      val predSuccess = result.filterKeys(part3(_)).forall { case (k, v) =>
        Await.result(v).get == k
      }
      val predFail = result.filterKeys(!part3(_)).values.forall { v =>
        Await.result(v.liftToTry).throwable == storeException
      }
      predFail && predSuccess
    }
  }

  property("multiMergeFromMultiSet returns previously stored value correctly") = {
    val intSg = implicitly[Semigroup[Int]]
    forAll { (ins: Map[Int, Int]) =>
      val store = newStore[Int, Int]

      /**
       * We merge the entire input map twice into the store. It should return values
       * stored the first time in the second call as old values.
       */
      MergeableStore.multiMergeFromMultiSet(store, ins)(intSg)
      val result = MergeableStore.multiMergeFromMultiSet(store, ins)(intSg)
      ins.forall { case (k, v) => Await.result(result(k)) == Some(v) }
    }
  }

  property("collectWithFailures should collect successes correctly") = {
    forAll { ins: Map[Int, Int] =>
      val fSleep = Future.sleep(Duration.fromMilliseconds(10))
      val futures = ins.mapValues(x => fSleep.map(_ => x))
      val fResult = MergeableStore.collectWithFailures(futures.iterator, futures.size)
      val (ss, _) = Await.result(fResult)
      ss.size == futures.size && ss.toSeq.sorted == ins.toSeq.sorted
    }
  }

  property("collectWithFailures should collect failures correctly") = {
    forAll { ins: Map[Int, Int] =>
      val throwable = new RuntimeException
      val fSleep = Future.sleep(Duration.fromMilliseconds(10))
      val futures = ins.mapValues(_ => fSleep before Future.exception(throwable))
      val fResult = MergeableStore.collectWithFailures(futures.iterator, futures.size)
      val (_, fails) = Await.result(fResult)
      fails.size == futures.size && fails.map(_._2).forall(_ == throwable)
    }
  }

  property("collectWithFailures should collect mix of successes and failures correctly") = {
    forAll { ins: Map[Int, Int] =>
      val throwable = new RuntimeException
      val fSleep = Future.sleep(Duration.fromMilliseconds(10))
      val successes = ins.filterKeys(_ % 2 == 0).mapValues(x => fSleep.map(_ => x))
      val failures = ins.filterKeys(_ %2 != 0).mapValues(_ => fSleep before Future.exception(throwable))
      val futures = successes ++ failures
      val fResult = MergeableStore.collectWithFailures(futures.iterator, futures.size)
      val (ss, fails) = Await.result(fResult)
      fails.size == failures.size && fails.map(_._2).forall(_ == throwable) &&
        (fails.size + ss.size) == ins.size

    }
  }
}
