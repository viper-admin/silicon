// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2011-2019 ETH Zurich.

package viper.silicon.rules

import viper.silicon.debugger.DebugExp
import viper.silicon.common.collections.immutable.InsertionOrderedSet
import viper.silicon.decider.RecordedPathConditions
import viper.silicon.interfaces.{Success, VerificationResult}
import viper.silicon.logger.records.structural.JoiningRecord
import viper.silicon.state.State
import viper.silicon.state.terms.{And, Or, Term}
import viper.silicon.utils.ast.{BigAnd, BigOr}
import viper.silicon.verifier.Verifier
import viper.silver.ast

import scala.annotation.unused

case class JoinDataEntry[D](s: State, data: D, pathConditions: RecordedPathConditions) {
  // Instead of merging states by calling State.merge,
  // we can directly merge JoinDataEntries to obtain new States,
  // and the join data entries themselves provide information about the path conditions to State.merge.
  def pathConditionAwareMerge(other: JoinDataEntry[D], v: Verifier): State = {
    val res = State.merge(this.s, this.pathConditions, other.s, other.pathConditions)
    v.stateConsolidator(s).consolidate(res, v)
  }

  def pathConditionAwareMergeWithoutConsolidation(other: JoinDataEntry[D], @unused v: Verifier): State = {
    State.merge(this.s, this.pathConditions, other.s, other.pathConditions)
  }
}

trait JoiningRules extends SymbolicExecutionRules {

  def join[D, JD](s: State, v: Verifier, resetState: Boolean = true)
                 (block: (State, Verifier, (State, D, Verifier) => VerificationResult) => VerificationResult)
                 (merge: Seq[JoinDataEntry[D]] => (State, JD))
                 (Q: (State, JD, Verifier) => VerificationResult)
                 : VerificationResult
}

object joiner extends JoiningRules {
  def join[D, JD](s: State, v: Verifier, resetState: Boolean = true)
                 (block: (State, Verifier, (State, D,  Verifier) => VerificationResult) => VerificationResult)
                 (merge: Seq[JoinDataEntry[D]] => (State, JD))
                 (Q: (State, JD, Verifier) => VerificationResult)
                 : VerificationResult = {

    var entries: Seq[JoinDataEntry[D]] = Vector.empty

    val joiningRecord = new JoiningRecord(s, v.decider.pcs)
    val uidJoin = v.symbExLog.openScope(joiningRecord)

    executionFlowController.locally(s, v)((s1, v1) => {
      val preMark = v1.decider.setPathConditionMark()
      val s2 = s1.copy(underJoin = true)

      block(s2, v1, (s3, data, v2) => {
        val s4 =
          if (resetState) {
            /* In order to prevent mismatches between different final states of the evaluation
             * paths that are to be joined, we reset certain state properties that may have been
             * affected by the evaluation - such as the store (by let-bindings) or the heap (by
             * state consolidations) to their initial values.
             */
            s3.copy(g = s1.g,
                    h = s1.h,
                    oldHeaps = s1.oldHeaps,
                    underJoin = s1.underJoin,
                    // TODO: Evaluation should not affect partiallyConsumedHeap, probably
                    ssCache = s1.ssCache,
                    partiallyConsumedHeap = s1.partiallyConsumedHeap,
                    invariantContexts = s1.invariantContexts,
                    retrying = s1.retrying)
          } else {
            // For more joins, state shouldn't be reset.
            s3
          }

        entries :+= JoinDataEntry(s4, data, v2.decider.pcs.after(preMark))
        Success()
      })
    }) combine {
      v.symbExLog.closeScope(uidJoin)
      if (entries.isEmpty) {
        /* No block data was collected, which we interpret as all branches through
         * the block being infeasible. In turn, we assume that the overall verification path
         * is infeasible. Instead of calling Q, we therefore terminate this path.
         */
        Success()
      } else {
        val (sJoined, dataJoined) = merge(entries)

        var feasibleBranches: List[Term] = Nil
        var feasibleBranchesExp: Option[List[ast.Exp]] = Option.when(withExp)(Nil)
        var feasibleBranchesExpNew: Option[List[ast.Exp]] = Option.when(withExp)(Nil)

        entries foreach (entry => {
          val pcs = entry.pathConditions.conditionalized
          val pcsExp = Option.when(withExp)(entry.pathConditions.conditionalizedExp)
          val comment = "Joined path conditions"
          v.decider.prover.comment(comment)
          v.decider.assume(pcs, Option.when(withExp)(DebugExp.createInstance(comment, InsertionOrderedSet(pcsExp.get))), enforceAssumption = false)
          feasibleBranches = And(entry.pathConditions.branchConditions) :: feasibleBranches
          feasibleBranchesExp = feasibleBranchesExp.map(fbe => BigAnd(entry.pathConditions.branchConditionExps.map(_._1)) :: fbe)
          feasibleBranchesExpNew = feasibleBranchesExpNew.map(fbe => BigAnd(entry.pathConditions.branchConditionExps.map(_._2.get)) :: fbe)
        })
        // Assume we are in a feasible branch
        v.decider.assume(Or(feasibleBranches), Option.when(withExp)(DebugExp.createInstance(Some("Feasible Branches"), feasibleBranchesExp.map(BigOr(_)), feasibleBranchesExpNew.map(BigOr(_)), InsertionOrderedSet.empty)))
        Q(sJoined, dataJoined, v)
      }
    }
  }
}
