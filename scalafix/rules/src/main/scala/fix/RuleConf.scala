package fix

import metaconfig.Conf
import scalafix.v1.Configuration

/** Reads `<Rule>.<key>` settings from `.scalafix.conf` without metaconfig's Scala 2 derivation macros. */
private[fix] object RuleConf:
  private def field(config: Configuration, rule: String, key: String): Option[Conf] =
    config.conf match
      case root: Conf.Obj =>
        root.field(rule).collect { case section: Conf.Obj => section }.flatMap(_.field(key))
      case _ => None

  /** An integer setting, or `default` when absent. */
  def int(config: Configuration, rule: String, key: String, default: Int): Int =
    field(config, rule, key).collect { case Conf.Num(n) => n.toInt }.getOrElse(default)

  /** A list-of-strings setting, or `default` when absent. */
  def strings(config: Configuration, rule: String, key: String, default: List[String]): List[String] =
    field(config, rule, key)
      .collect { case Conf.Lst(values) => values.collect { case Conf.Str(s) => s } }
      .getOrElse(default)
