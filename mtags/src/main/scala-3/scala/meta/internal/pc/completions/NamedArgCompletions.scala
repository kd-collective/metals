package scala.meta.internal.pc.completions

import scala.util.Try

import scala.meta.internal.mtags.MtagsEnrichments.*
import scala.meta.internal.pc.IndexedContext

import dotty.tools.dotc.ast.Trees.ValDef
import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.ContextOps.localContext
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.Flags.Method
import dotty.tools.dotc.core.NameKinds.DefaultGetterName
import dotty.tools.dotc.core.Names.Name
import dotty.tools.dotc.core.Symbols
import dotty.tools.dotc.core.Symbols.Symbol
import dotty.tools.dotc.core.Types.AndType
import dotty.tools.dotc.core.Types.AppliedType
import dotty.tools.dotc.core.Types.OrType
import dotty.tools.dotc.core.Types.TermRef
import dotty.tools.dotc.core.Types.Type
import dotty.tools.dotc.core.Types.TypeBounds
import dotty.tools.dotc.core.Types.WildcardType
import dotty.tools.dotc.util.SourcePosition

object NamedArgCompletions:

  def contribute(
      pos: SourcePosition,
      path: List[Tree],
      indexedContext: IndexedContext,
      clientSupportsSnippets: Boolean,
  )(using ctx: Context): List[CompletionValue] =
    path match
      case (ident: Ident) :: ValDef(_, _, _) :: Block(_, app: Apply) :: _
          if !isInfix(pos, app) =>
        contribute(
          Some(ident),
          app,
          indexedContext,
          clientSupportsSnippets,
        )
      case (ident: Ident) :: rest =>
        def getApplyForContextFunctionParam(path: List[Tree]): Option[Apply] =
          path match
            // fun(arg@@)
            case (app: Apply) :: _ => Some(app)
            // fun(arg@@), where fun(argn: Context ?=> SomeType)
            // recursively matched for multiple context arguments, e.g. Context1 ?=> Context2 ?=> SomeType
            case (_: DefDef) :: Block(List(_), _: Closure) :: rest =>
              getApplyForContextFunctionParam(rest)
            case _ => None
        val contribution =
          for
            app <- getApplyForContextFunctionParam(rest)
            if !isInfix(pos, app)
          yield contribute(
            Some(ident),
            app,
            indexedContext,
            clientSupportsSnippets,
          )
        contribution.getOrElse(Nil)
      case _ =>
        Nil
    end match
  end contribute

  private def isInfix(pos: SourcePosition, apply: Apply)(using ctx: Context) =
    apply.fun match
      case Select(New(_), _) => false
      case Select(_, name) if name.decoded == "apply" => false
      case Select(This(_), _) => false
      // is a select statement without a dot `qual.name`
      case sel @ Select(qual, _) if !sel.symbol.is(Flags.Synthetic) =>
        !(qual.span.end until sel.nameSpan.start)
          .map(pos.source.apply)
          .contains('.')
      case _ => false

  private def contribute(
      ident: Option[Ident],
      apply: Apply,
      indexedContext: IndexedContext,
      clientSupportsSnippets: Boolean,
  )(using context: Context): List[CompletionValue] =
    def isUselessLiteral(arg: Tree): Boolean =
      arg match
        case Literal(Constant(())) => true // unitLiteral
        case Literal(Constant(null)) => true // nullLiteral
        case _ => false

    def collectArgss(a: Apply): List[List[Tree]] =
      def stripContextFuntionArgument(argument: Tree): List[Tree] =
        argument match
          case Block(List(d: DefDef), _: Closure) =>
            d.rhs match
              case app: Apply =>
                app.args
              case b @ Block(List(_: DefDef), _: Closure) =>
                stripContextFuntionArgument(b)
              case _ => Nil
          case v => List(v)

      val args = a.args.flatMap(stripContextFuntionArgument)
      a.fun match
        case app: Apply => collectArgss(app) :+ args
        case _ => List(args)
    end collectArgss

    val method = apply.fun

    val argss = collectArgss(apply)

    // fallback for when multiple overloaded methods match the supplied args
    def fallbackFindMatchingMethods() =
      def maybeNameAndIndexedContext(
          method: Tree
      ): Option[(Name, IndexedContext)] =
        method match
          case Ident(name) => Some((name, indexedContext))
          case Select(This(_), name) => Some((name, indexedContext))
          case Select(from, name) =>
            val symbol = from.symbol
            val ownerSymbol =
              if symbol.is(Method) && symbol.owner.isClass then
                Some(symbol.owner)
              else Try(symbol.info.classSymbol).toOption
            ownerSymbol.map(sym =>
              (name, IndexedContext(context.localContext(from, sym)))
            )
          case Apply(fun, _) => maybeNameAndIndexedContext(fun)
          case _ => None
      val matchingMethods =
        for
          (name, indxContext) <- maybeNameAndIndexedContext(method)
          potentialMatches <- indxContext.findSymbol(name)
        yield potentialMatches.collect {
          case m
              if m.is(Flags.Method) &&
                m.vparamss.length >= argss.length &&
                Try(m.isAccessibleFrom(apply.symbol.info)).toOption
                  .getOrElse(false) &&
                m.vparamss
                  .zip(argss)
                  .reverse
                  .zipWithIndex
                  .forall { case (pair, index) =>
                    FuzzyArgMatcher(m.tparams)
                      .doMatch(allArgsProvided = index != 0)
                      .tupled(pair)
                  } =>
            m
        }
      matchingMethods.getOrElse(Nil)
    end fallbackFindMatchingMethods

    val matchingMethods: List[Symbols.Symbol] =
      if method.symbol.paramSymss.nonEmpty
      then
        val allArgsAreSupplied =
          val vparamss = method.symbol.vparamss
          vparamss.length == argss.length && vparamss
            .zip(argss)
            .lastOption
            .exists { case (baseParams, baseArgs) =>
              baseArgs.length == baseParams.length
            }
        // ```
        //  m(arg : Int)
        //  m(arg : Int, anotherArg : Int)
        //  m(a@@)
        // ```
        // complier will choose the first `m`, so we need to manually look for the other one
        if allArgsAreSupplied then
          val foundPotential = fallbackFindMatchingMethods()
          if foundPotential.contains(method.symbol) then foundPotential
          else method.symbol :: foundPotential
        else List(method.symbol)
      else fallbackFindMatchingMethods()
      end if
    end matchingMethods

    val allParams = matchingMethods.flatMap { methodSym =>
      val vparamss = methodSym.vparamss

      // get params and args we are interested in
      // e.g.
      // in the following case, the interesting args and params are
      // - params: [apple, banana]
      // - args: [apple, b]
      // ```
      // def curry(x: Int)(apple: String, banana: String) = ???
      // curry(1)(apple = "test", b@@)
      // ```
      val (baseParams, baseArgs) =
        vparamss.zip(argss).lastOption.getOrElse((Nil, Nil))

      val args = ident
        .map(i => baseArgs.filterNot(_ == i))
        .getOrElse(baseArgs)
        .filterNot(isUselessLiteral)

      val isNamed: Set[Name] = args.iterator
        .zip(baseParams.iterator)
        // filter out synthesized args and default arg getters
        .filterNot {
          case (arg, _) if arg.symbol.denot.is(Flags.Synthetic) => true
          case (Ident(name), _) => name.is(DefaultGetterName) // default args
          case (Select(Ident(_), name), _) =>
            name.is(DefaultGetterName) // default args for apply method
          case _ => false
        }
        .map {
          case (NamedArg(name, _), _) => name
          case (_, param) => param.name
        }
        .toSet

      baseParams.filterNot(param =>
        isNamed(param.name) ||
          param.denot.is(
            Flags.Synthetic
          ) // filter out synthesized param, like evidence
      )
    }

    val prefix =
      ident
        .map(_.name.toString)
        .getOrElse("")
        .replace(Cursor.value, "")
    val params: List[Symbol] =
      allParams
        .filter(param => param.name.startsWith(prefix))
        .distinctBy(sym => (sym.name, sym.info))

    val completionSymbols = indexedContext.scopeSymbols
    def matchingTypesInScope(paramType: Type): List[String] =
      completionSymbols
        .collect {
          case sym
              if sym.info <:< paramType && sym.isTerm && !sym.info.isErroneous && !sym.info.isNullType && !sym.info.isNothingType && !sym
                .is(Flags.Method) && !sym.is(Flags.Synthetic) =>
            sym.decodedName
        }
        .filter(name => name != "Nil" && name != "None")
        .sorted

    def findDefaultValue(param: Symbol): String =
      val matchingType = matchingTypesInScope(param.info)
      if matchingType.size == 1 then s":${matchingType.head}"
      else if matchingType.size > 1 then s"|???,${matchingType.mkString(",")}|"
      else ":???"

    def fillAllFields(): List[CompletionValue] =
      val suffix = "autofill"
      def shouldShow =
        allParams.exists(param => param.name.startsWith(prefix))
      def isExplicitlyCalled = suffix.startsWith(prefix)
      def hasParamsToFill = allParams.count(!_.is(Flags.HasDefault)) > 1
      if clientSupportsSnippets && matchingMethods.length == 1 && (shouldShow || isExplicitlyCalled) && hasParamsToFill
      then
        val editText = allParams.zipWithIndex
          .collect {
            case (param, index) if !param.is(Flags.HasDefault) =>
              s"${param.nameBackticked.replace("$", "$$")} = $${${index + 1}${findDefaultValue(param)}}"
          }
          .mkString(", ")
        List(
          CompletionValue.Autofill(
            editText
          )
        )
      else List.empty
      end if
    end fillAllFields

    def findPossibleDefaults(): List[CompletionValue] =
      params.flatMap { param =>
        val allMembers = matchingTypesInScope(param.info)
        allMembers.map { memberName =>
          val editText =
            param.nameBackticked + " = " + memberName + " "
          CompletionValue.namedArg(
            label = editText,
            param,
          )
        }
      }

    params.map(p =>
      CompletionValue.namedArg(
        s"${p.nameBackticked} = ",
        p,
      )
    ) ::: findPossibleDefaults() ::: fillAllFields()
  end contribute

  extension (method: Symbols.Symbol)
    def vparamss(using Context) = method.filteredParamss(_.isTerm)
    def tparams(using Context) = method.filteredParamss(_.isType).flatten
    def filteredParamss(f: Symbols.Symbol => Boolean)(using Context) =
      method.paramSymss.filter(params => params.forall(f))
end NamedArgCompletions

class FuzzyArgMatcher(tparams: List[Symbols.Symbol])(using Context):

  /**
   * A heuristic for checking if the passed arguments match the method's arguments' types.
   * For non-polymorphic methods we use the subtype relation (`<:<`)
   * and for polymorphic methods we use a heuristic.
   * We check the args types not the result type.
   */
  def doMatch(
      allArgsProvided: Boolean
  )(expectedArgs: List[Symbols.Symbol], actualArgs: List[Tree]) =
    (expectedArgs.length == actualArgs.length ||
      (!allArgsProvided && expectedArgs.length >= actualArgs.length)) &&
      actualArgs.zipWithIndex.forall {
        case (Ident(name), _) if name.endsWith(Cursor.value) => true
        case (NamedArg(name, arg), _) =>
          expectedArgs.exists { expected =>
            expected.name == name && (!arg.hasType || arg.typeOpt.unfold
              .fuzzyArg_<:<(expected.info))
          }
        case (arg, i) =>
          !arg.hasType || arg.typeOpt.unfold.fuzzyArg_<:<(expectedArgs(i).info)
      }

  extension (arg: Type)
    def fuzzyArg_<:<(expected: Type) =
      if tparams.isEmpty then arg <:< expected
      else arg <:< substituteTypeParams(expected)
    def unfold =
      arg match
        case arg: TermRef => arg.underlying
        case e => e

  private def substituteTypeParams(t: Type): Type =
    t match
      case e if tparams.exists(_ == e.typeSymbol) =>
        val matchingParam = tparams.find(_ == e.typeSymbol).get
        matchingParam.info match
          case b @ TypeBounds(_, _) => WildcardType(b)
          case _ => WildcardType
      case o @ OrType(e1, e2) =>
        OrType(substituteTypeParams(e1), substituteTypeParams(e2), o.isSoft)
      case AndType(e1, e2) =>
        AndType(substituteTypeParams(e1), substituteTypeParams(e2))
      case AppliedType(et, eparams) =>
        AppliedType(et, eparams.map(substituteTypeParams))
      case _ => t

end FuzzyArgMatcher
