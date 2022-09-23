package java.lang.ref

import org.junit.Test
import org.junit.Assert._
import org.junit.Assume._

import scala.scalanative.meta.LinktimeInfo.isWeakReferenceSupported
import scala.scalanative.annotation.nooptimize
import scala.scalanative.buildinfo.ScalaNativeBuildInfo

import scala.scalanative.runtime.GC
import org.scalanative.testsuite.utils.Platform

// "AfterGC" tests are very sensitive to optimizations,
// both by Scala Native and LLVM.
class WeakReferenceTest {

  case class A()

  def gcAssumption(): Unit = {
    assumeTrue(
      "WeakReferences work only on Commix and Immix GC",
      isWeakReferenceSupported
    )
  }

  @noinline def allocWeakRef(
      referenceQueue: ReferenceQueue[A]
  ): WeakReference[A] = {
    var a = A()
    val weakRef = new WeakReference(a, referenceQueue)
    assertEquals("get() should return object reference", weakRef.get(), A())
    a = null
    weakRef
  }

  @nooptimize @Test def addsToReferenceQueueAfterGC(): Unit = {
    assumeFalse(
      "In the CI Scala 3 sometimes SN fails to clean weak references in some of Windows build configurations",
      ScalaNativeBuildInfo.scalaVersion.startsWith("3.") &&
        Platform.isWindows
    )

    def assertEventuallyIsCollected(
        clue: String,
        ref: WeakReference[_],
        deadline: Long
    ): Unit = {
      ref.get() match {
        case null =>
          assertTrue("collected but not enqueded", ref.isEnqueued())
        case v =>
          if (System.currentTimeMillis() < deadline) {
            // Give GC something to collect
            locally {
              val _ = Seq.fill(1000)(new Object {})
            }
            Thread.sleep(200)
            GC.collect()
            assertEventuallyIsCollected(clue, ref, deadline)
          } else {
            fail(
              s"$clue - expected that WeakReference would be collected, but it contains value ${v}"
            )
          }
      }
    }

    gcAssumption()
    val refQueue = new ReferenceQueue[A]()
    val weakRef1 = allocWeakRef(refQueue)
    val weakRef2 = allocWeakRef(refQueue)
    val weakRefList = List(weakRef1, weakRef2)

    GC.collect()
    def newDeadline() = System.currentTimeMillis() + 60 * 1000
    assertEventuallyIsCollected("weakRef1", weakRef1, deadline = newDeadline())
    assertEventuallyIsCollected("weakRef2", weakRef2, deadline = newDeadline())

    assertEquals("weakRef1", null, weakRef1.get())
    assertEquals("weakRef2", null, weakRef2.get())
    val a = refQueue.poll()
    assertNotNull("a was null", a)
    val b = refQueue.poll()
    assertNotNull("b was null", b)
    assertTrue("!contains a", weakRefList.contains(a))
    assertTrue("!contains b", weakRefList.contains(b))
    assertNotEquals(a, b)
    assertEquals("pool not null", null, refQueue.poll())
  }

  @Test def clear(): Unit = {
    val refQueue = new ReferenceQueue[A]()
    val a = A()
    val weakRef = new WeakReference(a, refQueue)

    assertEquals(refQueue.poll(), null)

    weakRef.clear()
    assertEquals(weakRef.get(), null)
    assertEquals(refQueue.poll(), weakRef)
    assertEquals(refQueue.poll(), null)
  }

  @Test def enqueue(): Unit = {
    val refQueue = new ReferenceQueue[A]()
    val a = A()
    val weakRef = new WeakReference(a, refQueue)

    assertEquals(refQueue.poll(), null)

    weakRef.enqueue()
    assertEquals(weakRef.get(), a)
    assertEquals(refQueue.poll(), weakRef)
    assertEquals(refQueue.poll(), null)
  }

}
