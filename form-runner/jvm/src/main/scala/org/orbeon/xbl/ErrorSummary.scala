/**
 * Copyright (C) 2013 Orbeon, Inc.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version
 * 2.1 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.xbl

import org.orbeon.dom
import org.orbeon.dom.saxon.DocumentWrapper
import org.orbeon.oxf.fr.FormRunner
import org.orbeon.oxf.util.CollectionUtils._
import org.orbeon.oxf.util.CoreUtils._
import org.orbeon.oxf.xforms.NodeInfoFactory._
import org.orbeon.oxf.xforms.action.XFormsAPI
import org.orbeon.oxf.xforms.action.XFormsAPI._
import org.orbeon.oxf.xforms.control.Controls.AncestorOrSelfIterator
import org.orbeon.oxf.xforms.control.XFormsComponentControl
import org.orbeon.oxf.xforms.model.InstanceData
import org.orbeon.saxon.om.{DocumentInfo, NodeInfo}
import org.orbeon.scaxon.Implicits._
import org.orbeon.scaxon.NodeConversions._
import org.orbeon.scaxon.SimplePath._
import org.orbeon.xforms.XFormsId

import scala.collection.JavaConverters._
import scala.collection.Searching._
import scala.collection.SeqLike
import scala.collection.generic.IsSeqLike
import scala.math.Ordering

object ErrorSummary {

  private val ErrorSummaryIds = List("error-summary-control-top", "error-summary-control-bottom")

  private val MaxRepeatDepthForSorting = 4

  private def findErrorSummaryControl = (
    ErrorSummaryIds
    flatMap      { id ⇒ Option(inScopeContainingDocument.getControlByEffectiveId(id)) }
    collectFirst { case c: XFormsComponentControl ⇒ c }
  )

  private def findErrorSummaryModel =
    findErrorSummaryControl flatMap (_.nestedContainer.models find (_.getId == "fr-error-summary-model"))

  private def findErrorsInstance =
    findErrorSummaryModel map (_.getInstance("fr-errors-instance"))

  private def findStateInstance =
    findErrorSummaryModel map (_.getInstance("fr-state-instance"))

  //@XPathFunction
  def topLevelSectionNameForControlId(absoluteControlId: String): Option[String] =
    Option(inScopeContainingDocument.getControlByEffectiveId(XFormsId.absoluteIdToEffectiveId(absoluteControlId))) flatMap {
      control ⇒
        val sectionsIt =
          new AncestorOrSelfIterator(control) collect {
            case section: XFormsComponentControl if section.localName == "section" ⇒ section
          }

        sectionsIt.lastOption() map (_.getId) flatMap FormRunner.controlNameFromIdOpt
    }

  // Return the subset of section names passed which contain errors in the global error summary
  def topLevelSectionsWithErrors(sectionNamesSet: Set[String], onlyVisible: Boolean): Map[String, (Int, Int)] =
    findErrorsInstance match {
      case Some(errorsInstance) ⇒

        val relevantErrorsIt = {

          def allErrorsIt =
            (errorsInstance.rootElement / "error").iterator

          def visibleErrorsIt =
            findErrorSummaryModel.iterator flatMap (m ⇒ asScalaIterator(m.getVariable("visible-errors"))) collect {
              case n: NodeInfo ⇒ n
            }

          (if (onlyVisible) visibleErrorsIt else allErrorsIt) filter (_.attValue("level") == "error")
        }

        val sectionNameErrors = (
          relevantErrorsIt
          map    { e ⇒ e.attValue("section-name") → e }
          filter { case (name, _) ⇒ sectionNamesSet(name) }
          toList
        )

        sectionNameErrors groupBy (_._1) map  { case (sectionName, list) ⇒

          val requiredButEmptyCount =
            list count (_._2.attValue("required-empty") == true.toString)

          sectionName → (requiredButEmptyCount, list.size - requiredButEmptyCount)
        }

      case None ⇒
        Map.empty
    }

  // Update the iteration in a control's absolute id
  //@XPathFunction
  def updateIteration(absoluteId: String, repeatAbsoluteId: String, fromIterations: Array[Int], toIterations: Array[Int]): String = {

    val effectiveId = XFormsId.absoluteIdToEffectiveId(absoluteId)
    val prefixedId  = XFormsId.getPrefixedId(effectiveId)

    val repeatEffectiveId = XFormsId.absoluteIdToEffectiveId(repeatAbsoluteId)
    val repeatPrefixedId  = XFormsId.getPrefixedId(repeatEffectiveId)

    val ancestorRepeats = inScopeContainingDocument.getStaticOps.getAncestorRepeatIds(prefixedId)

    if (ancestorRepeats contains repeatPrefixedId) {
      // Control is a descendant of the repeat so might be impacted

      val idIterationPairs = XFormsId.getEffectiveIdSuffixParts(effectiveId) zip ancestorRepeats
      val iterationsMap    = fromIterations zip toIterations toMap

      val newIterations = idIterationPairs map {
        case (fromIteration, `repeatPrefixedId`) if iterationsMap.contains(fromIteration) ⇒ iterationsMap(fromIteration).toString.asInstanceOf[AnyRef]
        case (iteration, _)                                                               ⇒ iteration.toString.asInstanceOf[AnyRef]
      }

      val newEffectiveId = XFormsId.buildEffectiveId(prefixedId, newIterations)

      XFormsId.effectiveIdToAbsoluteId(newEffectiveId)

    } else
      absoluteId // id is not impacted
  }

  private val Digits = "0" * 5

  // Return a sorting string for the given control absolute id, taking repeats into account
  //@XPathFunction
  def controlSortString(absoluteId: String, repeatsDepth: Int): String = {

    val effectiveId = XFormsId.absoluteIdToEffectiveId(absoluteId)
    val prefixedId  = XFormsId.getPrefixedId(effectiveId)

    val controlPosition =
      inScopeContainingDocument.getStaticOps.getControlPosition(prefixedId).get // argument must be a view control

    val repeatsFromLeaf =
      inScopeContainingDocument.getStaticOps.getAncestorRepeats(prefixedId)

    def iterations =
      XFormsId.getEffectiveIdSuffixParts(effectiveId)

    // Use arrays indexes to *attempt* to be more efficient
    // NOTE: Profiler shows that the 2 calls to ofDim take 50% of the method time
    val result = Array.ofDim[Int](repeatsDepth * 2 + 1)

    locally {
      var i = (repeatsFromLeaf.size - 1) * 2
      for (r ← repeatsFromLeaf) {
        result(i) = r.index
        i -= 2
      }
    }

    locally {
      var i = 1
      for (iteration ← iterations) {
        result(i) = iteration
        i += 2
      }
    }

    result(repeatsFromLeaf.size * 2) = controlPosition

    def padWithZeros(i: Int) = {
      val s    = i.toString
      val diff = Digits.length - s.length

      if (diff > 0) Digits.substring(0, diff) + s else s
    }

    val resultString = Array.ofDim[String](result.length)

    for (i ← result.indices)
      resultString(i) = padWithZeros(result(i))

    resultString mkString "-"
  }

  //@XPathFunction
  def removeUpdateOrInsertError(
    errorsInstanceDoc: DocumentInfo,
    absoluteTargetId : String,
    eventName        : String,
    controlPosition  : Int,
    bindingOpt       : Option[NodeInfo],
    eventLevelOpt    : Option[String],
    alertOpt         : Option[String],
    labelOpt         : Option[String]
  ): Unit = {

    val rootElem = errorsInstanceDoc.rootElement

    val currentErrorOpt = Option(errorsInstanceDoc.selectID(absoluteTargetId))

    val actualEventLevel =
      eventName match {
        case "xxforms-constraints-changed" ⇒ eventLevelOpt
        case "xforms-invalid"              ⇒ Some("error") // `xforms-invalid` indicates an error level, but doesn't always have constraints associated
        case _                             ⇒ None
      }

    def requiredEmpty =
      bindingOpt exists (b ⇒ InstanceData.getRequired(b) && b.stringValue.isEmpty)

    val previousStatusIsValid =
      findStateInstance exists (i ⇒ (i.rootElement elemValue "valid") == "true")

    def updateValidStatus(value: Boolean) =
      findStateInstance foreach { stateInstance ⇒
        XFormsAPI.setvalue(
          ref   = stateInstance.rootElement / "valid",
          value = value.toString
        )
      }

    def updateValidStatusForDelete(currentError: NodeInfo) =
      if ((currentError attValue "level") == "error")
        updateValidStatus(
          ! (errorsInstanceDoc.rootElement / * exists (_.attValue("level") == "error"))
        )

    def updateValidStatusForInsert(actualEventLevel: String) =
      if (previousStatusIsValid && actualEventLevel == "error")
          updateValidStatus(false)

    (currentErrorOpt, actualEventLevel, alertOpt) match {
      case (Some(currentError), None, _) ⇒
        XFormsAPI.delete(currentError)
        updateValidStatusForDelete(currentError)
      case (Some(currentError), _, None) ⇒
        XFormsAPI.delete(currentError)
        updateValidStatusForDelete(currentError)
      case (Some(currentError), Some(actualEventLevel), Some(alert)) ⇒

        XFormsAPI.setvalue(currentError /@ "level"         , actualEventLevel)
        XFormsAPI.setvalue(currentError /@ "alert"         , alert)
        XFormsAPI.setvalue(currentError /@ "label"         , labelOpt getOrElse "")
        XFormsAPI.setvalue(currentError /@ "required-empty", requiredEmpty.toString)

        updateValidStatusForInsert(actualEventLevel)

      case (None, Some(actualEventLevel), Some(alert)) ⇒

        // In order to make insertion efficient, the `<error>` elements are kept sorted, without
        // any other children nodes except namespace nodes at the beginning. We then use a binary
        // search to find the insertion point.

        val newElemInfo =
          elementInfo(
            "error",
            List(
              attributeInfo("id",             absoluteTargetId),
              attributeInfo("position",       controlPosition.toString),
              attributeInfo("label",          actualEventLevel),
              attributeInfo("alert",          alert),
              attributeInfo("level",          labelOpt getOrElse ""),
              attributeInfo("section-name",   topLevelSectionNameForControlId(absoluteTargetId) getOrElse ""),
              attributeInfo("required-empty", requiredEmpty.toString)
            )
          )

        // We work with the underlying DOM here
        val newElemForSorting  = unsafeUnwrapElement(newElemInfo)
        val rootElemDomContent = unsafeUnwrapElement(rootElem).content.asScala

        implicit object NodeOrdering extends Ordering[dom.Node] {
          def compare(x: dom.Node, y: dom.Node): Int = (x, y) match {
            case (n1: dom.Element, n2: dom.Element) ⇒
              controlSortString(n1.attributeValue("id"), MaxRepeatDepthForSorting)
                .compareTo(controlSortString(n2.attributeValue("id"), MaxRepeatDepthForSorting))
            case (n1: dom.Namespace, n2: dom.Element)   ⇒ -1                       // all elements are after the namespace nodes
            case (n1: dom.Element,   n2: dom.Namespace) ⇒ +1                       // all elements are after the namespace nodes
            case (n1: dom.Namespace, n2: dom.Namespace) ⇒ n1.uri.compareTo(n2.uri) // predictable order even though they won't be sorted
            case _                                      ⇒ throw new IllegalStateException
          }
        }

        import BinarySearching._

        val insertionPoint =
          rootElemDomContent.binarySearch(newElemForSorting, 0, rootElemDomContent.length) match {
            case InsertionPoint(p) ⇒ p
            case Found(i)          ⇒ throw new IllegalStateException // there must not be an existing error and we know because we search for it above
          }

        val afterElemList =
          (rootElemDomContent.nonEmpty && insertionPoint > 0 && ! rootElemDomContent(insertionPoint - 1).isInstanceOf[dom.Namespace]) list
            errorsInstanceDoc.asInstanceOf[DocumentWrapper].wrap(rootElemDomContent(insertionPoint - 1))

        XFormsAPI.insert(
          into   = rootElem,
          after  = afterElemList,
          origin = newElemInfo,
          updateRepeats = false
        )

        updateValidStatusForInsert(actualEventLevel)

      case _ ⇒
    }
  }
}

// This is lifted from scala's `Searching`, as we have a `Buffer` and the original implementation only enables binary search
// if the collection is an `IndexedSearch`. Since our `Buffer` is not an `IndexedSearch`, the test fails and linear search
// is used instead. But we know our `Buffer` is backed by an indexed Java collection.
object BinarySearching {
  class BinarySearchImpl[A, Repr](coll: SeqLike[A, Repr]) {
    def binarySearch[B >: A](elem: B, from: Int, to: Int)(implicit ord: Ordering[B]): SearchResult = {
      if (to == from) InsertionPoint(from) else {
        val idx = from+(to-from-1)/2
        math.signum(ord.compare(elem, coll(idx))) match {
          case -1 => binarySearch(elem, from, idx)(ord)
          case  1 => binarySearch(elem, idx + 1, to)(ord)
          case  _ => Found(idx)
        }
      }
    }
  }

  implicit def binarySearch[Repr, A](coll: Repr)
    (implicit fr: IsSeqLike[Repr]): BinarySearchImpl[fr.A, Repr] = new BinarySearchImpl(fr.conversion(coll))
}