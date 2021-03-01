package rpi.inference.teacher.query

import rpi.Names
import rpi.builder.{CheckExtender, Folding}
import rpi.inference.context._
import rpi.inference.Hypothesis
import rpi.inference.annotation.AccessAnalysis.Depth
import rpi.inference.annotation.Hint
import rpi.util.ast.Expressions._
import rpi.util.Namespace
import rpi.util.ast.Statements._
import rpi.util.ast._
import viper.silver.ast

/**
  * A program builder that builds queries.
  *
  * @param context The context.
  */
class QueryBuilder(protected val context: Context) extends CheckExtender with Folding {
  /**
    * The namespace used to generate unique identifiers.
    */
  private var namespace: Namespace = _

  private var query: PartialQuery = _

  def framingQuery(hypothesis: Hypothesis): Query = {
    /**
      * Helper method that inhales the given expression conjunct-wise. The expression is implicitly rewritten to have
      * its conjuncts at the top level by pushing implications inside.
      *
      * @param expression The expression to inhale.
      * @param guards     The guards collected so far.
      */
    def inhale(expression: ast.Exp, guards: Seq[ast.Exp] = Seq.empty): Unit =
      expression match {
        case ast.And(left, right) =>
          inhale(left, guards)
          inhale(right, guards)
        case ast.Implies(guard, guarded) =>
          inhale(guarded, guards :+ guard)
        case conjunct =>
          // compute info used to extract framing sample
          val info = conjunct match {
            case ast.FieldAccessPredicate(location, _) => ValueInfo(location)
            case ast.PredicateAccessPredicate(location, _) => ValueInfo(location)
            case _ => ast.NoInfo
          }
          // inhale conjunct
          val implication = makeImplication(makeAnd(guards), conjunct)
          addInhale(implication, info)
      }

    // reset
    clear()
    // build method for each specification predicate
    val methods = hypothesis
      .predicates
      .map { predicate =>
        val body = makeScope {
          // get specification instance
          val specification = context.specification(predicate.name)
          val instance = IdentityInstance(specification)
          // save state snapshot
          saveSnapshot(instance)
          // inhale predicate body
          val body = hypothesis.getPredicateBody(instance)
          inhale(body)
        }
        // build method
        val name = namespace.uniqueIdentifier(s"check_${predicate.name}")
        buildMethod(name, body)
      }

    // dummy hypothesis
    val dummy = {
      // process predicates
      val predicates = hypothesis
        .predicates
        .flatMap { predicate =>
          if (Names.isRecursive(predicate.name)) {
            // remove predicate body
            val empty = predicate.copy(body = None)(ast.NoPosition, ast.NoInfo, ast.NoTrafos)
            Some(empty)
          } else {
            // ignore specification predicates
            None
          }
        }
      // create hypothesis
      Hypothesis(predicates)
    }

    // build program
    val program = buildProgram(methods, dummy)
    println(program)
    query(program)
  }

  def basicQuery(checks: Seq[Check], hypothesis: Hypothesis): Query = {
    // reset
    clear()
    // build method for each check
    val methods = checks.map { check =>
      val body = processCheck(check, hypothesis)
      buildMethod(check.name, body)
    }
    // build program
    val program = buildProgram(methods, hypothesis)
    println(program)
    query(program)
  }

  override protected def processCut(cut: Cut)(implicit hypothesis: Hypothesis): Unit =
    addStatement(cut.havoc)

  protected override def processHinted(hinted: ast.Stmt)(implicit hypothesis: Hypothesis, hints: Seq[Hint]): Unit =
    hinted match {
      case ast.Seqn(statements, _) =>
        statements.foreach { statement => processHinted(statement) }
      case ast.Inhale(expression) =>
        expression match {
          case placeholder: ast.PredicateAccessPredicate =>
            // get specification
            val instance = ValueInfo.value[Instance](placeholder)
            val body = hypothesis.getPredicateBody(instance)
            // inhale placeholder predicate
            if (configuration.noInlining()) {
              // inhale and unfold predicate
              addInhale(placeholder)
              addUnfold(placeholder)
            } else {
              // inhale predicate body
              val info = new ValueInfo(instance) with Comment
              addInhale(body, info)
            }
            // unfold and save
            val maxDepth = if (configuration.useAnnotations()) check.depth(hypothesis) else 0
            unfold(body)(maxDepth, hypothesis)
            saveSnapshot(instance)
          case _ =>
            addInhale(expression)
        }
      case ast.Exhale(expression) =>
        expression match {
          case placeholder: ast.PredicateAccessPredicate =>
            // get specification
            val instance = ValueInfo.value[Instance](placeholder)
            val body = hypothesis.getPredicateBody(instance)
            // save and fold
            implicit val label: String = saveSnapshot(instance)
            if (configuration.useAnnotations()) {
              val maxDepth = check.depth(hypothesis)
              foldWithAnnotations(body, hints)(maxDepth, hypothesis, savePermission)
            } else {
              val maxDepth = configuration.heuristicsFoldDepth()
              fold(body)(maxDepth, hypothesis, savePermission)
            }
            // exhale placeholder predicate
            if (configuration.noInlining()) {
              // fold and exhale predicate
              addFold(placeholder)
              addExhale(placeholder)
            } else {
              // exhale predicate body
              val info = new ValueInfo(instance) with Comment
              addExhale(body, info)
            }
          case _ =>
            addExhale(expression)
        }
      case other =>
        addStatement(other)
    }

  private def saveSnapshot(instance: Instance): String = {
    // generate unique snapshot label
    val name = namespace.uniqueIdentifier(Names.snapshot, Some(0))
    query.addSnapshot(name, instance)
    // save values of variables
    instance
      .arguments
      .foreach {
        case variable: ast.LocalVar =>
          saveValue(s"${name}_${variable.name}", variable)
      }
    // save values of atoms
    instance
      .actualAtoms
      .zipWithIndex
      .foreach {
        case (atom, index) =>
          saveAtom(s"${name}_$index", atom)
      }
    // add label
    addLabel(name)
    // return snapshot label
    name
  }

  /**
    * Saves the permission value if the given expression is a field access or a predicate access.
    *
    * @param expression The expression.
    * @param guards     The collected guards.
    * @param label      The implicitly passed label for the current state snapshot
    */
  private def savePermission(expression: ast.Exp, guards: Seq[ast.Exp])(implicit label: String): Unit =
    expression match {
      case ast.FieldAccessPredicate(resource, _) =>
        savePermission(resource, guards)
      case ast.PredicateAccessPredicate(resource, _) =>
        savePermission(resource, guards)
      case _ => // do nothing
    }

  /**
    * Saves the permission value of the given location access.
    *
    * @param access The access.
    * @param guards The collected guards.
    * @param label  The implicitly passed label for the current state snapshot.
    */
  private def savePermission(access: ast.LocationAccess, guards: Seq[ast.Exp])(implicit label: String): Unit = {

    def extractConditions(expression: ast.Exp): Seq[ast.Exp] =
      expression match {
        case access: ast.FieldAccess =>
          val name = query.name(label, access)
          val variable = ast.LocalVar(name, ast.Perm)()
          Seq(ast.PermGtCmp(variable, ast.NoPerm()())())
        case _ => Seq.empty
      }

    // generate unique name
    val name = namespace.uniqueIdentifier(Names.permission, Some(0))
    query.addName(label, access, name)

    //  conditions under which we have the permissions to talk about the given access.
    val conditions = access match {
      case ast.FieldAccess(receiver, _) =>
        extractConditions(receiver)
      case ast.PredicateAccess(arguments, _) =>
        arguments.flatMap { argument => extractConditions(argument) }
    }
    // conditionally save value
    val value = makeTernary(guards ++ conditions, makeCurrent(access), makeNone)
    saveValue(name, value)
  }

  /**
    * Saves the value of the given atom in a variable with the given name.
    *
    * @param name The name of the variable.
    * @param atom The atom to save.
    */
  private def saveAtom(name: String, atom: ast.Exp): Unit =
    if (configuration.noBranching()) saveValue(name, atom)
    else {
      // create conditional assignment
      val variable = makeBoolean(name)
      val thenBody = makeAssign(variable, makeTrue)
      val elseBody = makeAssign(variable, makeFalse)
      addConditional(atom, thenBody, elseBody)
    }

  /**
    * Saves the value of the given expression in a variable with the given name.
    *
    * @param name       The name of the variable.
    * @param expression The expression to save.
    */
  private def saveValue(name: String, expression: ast.Exp): Unit = {
    // create variable and assignment
    val variable = makeVariable(name, expression.typ)
    addAssign(variable, expression)
  }

  private def buildMethod(name: String, body: ast.Seqn): ast.Method = {
    val declarations = body
      .deepCollect { case variable: ast.LocalVar => variable }
      .distinct
      .map { variable => makeDeclaration(variable) }
    val scoped = makeSequence(body.ss, declarations)
    ast.Method(name, Seq.empty, Seq.empty, Seq.empty, Seq.empty, Some(scoped))()
  }

  private def clear(): Unit = {
    namespace = context.namespace
    query = new PartialQuery
  }
}