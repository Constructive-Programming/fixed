package fixed.parsing

import fixed.ast.{Tree, Trees}
import fixed.util.{Reporter, SourceFile}

/** Shared parse helpers and tree walkers for the parser suites. */
trait ParserTestSupport:

  protected def parse(input: String): (Tree, Reporter) =
    val src = SourceFile.fromString("<test>", input)
    val rep = new Reporter(src)
    val tree = Parser.parse(src, rep)
    (tree, rep)

  protected def parseFile(path: String): (Tree, Reporter) =
    val src = SourceFile.fromPath(path)
    val rep = new Reporter(src)
    val tree = Parser.parse(src, rep)
    (tree, rep)

  /** Walk the tree gathering every Trees.Error. Used by the recovery tests
    * above; the flattening-vs-shape distinction is tested elsewhere.
    */
  protected def collectAllErrors(t: Tree): List[Trees.Error] =
    t match
      case e: Trees.Error =>
        e :: e.recovered.flatMap(collectAllErrors)
      case Trees.CompilationUnit(items, _)             => items.flatMap(collectAllErrors)
      case Trees.FnDecl(_, _, params, ret, _, body, _) =>
        params.flatMap(p => collectAllErrors(p.typeAnn)) ++
          ret.toList.flatMap(collectAllErrors) ++ body.toList.flatMap(collectAllErrors)
      case Trees.Block(stmts, _)           => stmts.flatMap(collectAllErrors)
      case Trees.LetExpr(p, init, _)       => collectAllErrors(p) ++ collectAllErrors(init)
      case Trees.IfExpr(c, t1, e, _)       => collectAllErrors(c) ++ collectAllErrors(t1) ++ collectAllErrors(e)
      case Trees.BinOp(_, l, r, _)         => collectAllErrors(l) ++ collectAllErrors(r)
      case Trees.UnaryOp(_, op, _)         => collectAllErrors(op)
      case Trees.Apply(fn, args, _)        => collectAllErrors(fn) ++ args.flatMap(collectAllErrors)
      case Trees.MethodCall(r, _, args, _) => collectAllErrors(r) ++ args.toList.flatten.flatMap(collectAllErrors)
      case Trees.StaticCall(_, _, args, _) => args.toList.flatten.flatMap(collectAllErrors)
      case Trees.TupleExpr(es, _)          => es.flatMap(collectAllErrors)
      case Trees.ListExpr(es, _)           => es.flatMap(collectAllErrors)
      case _                               => Nil

  /** Walk the tree and collect every Ident's name. Used by the no-leak
    * test above; deliberately exhaustive over the productions the
    * smoke-test inputs can produce.
    */
  protected def collectIdents(t: Tree): List[String] =
    t match
      case Trees.Ident(n, _)                           => List(n)
      case Trees.CompilationUnit(items, _)             => items.flatMap(collectIdents)
      case Trees.FnDecl(_, _, params, ret, _, body, _) =>
        params.flatMap(p => collectIdents(p.typeAnn) ++ p.default.toList.flatMap(collectIdents)) ++
          ret.toList.flatMap(collectIdents) ++ body.toList.flatMap(collectIdents)
      case Trees.Block(stmts, _)           => stmts.flatMap(collectIdents)
      case Trees.LetExpr(p, init, _)       => collectIdents(p) ++ collectIdents(init)
      case Trees.IfExpr(c, t1, e, _)       => collectIdents(c) ++ collectIdents(t1) ++ collectIdents(e)
      case Trees.BinOp(_, l, r, _)         => collectIdents(l) ++ collectIdents(r)
      case Trees.UnaryOp(_, op, _)         => collectIdents(op)
      case Trees.Apply(fn, args, _)        => collectIdents(fn) ++ args.flatMap(collectIdents)
      case Trees.MethodCall(r, _, args, _) => collectIdents(r) ++ args.toList.flatten.flatMap(collectIdents)
      case Trees.StaticCall(_, _, args, _) => args.toList.flatten.flatMap(collectIdents)
      case Trees.Error(rec, _)             => rec.flatMap(collectIdents)
      case _                               => Nil
