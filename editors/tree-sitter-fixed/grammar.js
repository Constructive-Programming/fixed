/**
 * Tree-sitter grammar for Fixed (spec/syntax_grammar.ebnf v0.4.16).
 *
 * This grammar is tuned for highlighting, not for conformance: it accepts
 * the shapes the spec allows without trying to be the reference parser. The
 * real parser is the Scala compiler; this grammar exists so Neovim, Helix,
 * Zed, and Emacs can colour Fixed and select syntax structurally.
 *
 * Indentation is handled by src/scanner.c (INDENT/DEDENT). Newlines are
 * extras, so bracketed lists and multi-line applications need no special
 * suppression rules.
 */

const PREC = {
  pipe: 1,
  implies: 2,
  or: 3,
  and: 4,
  compare: 5,
  add: 6,
  mul: 7,
  ascription: 8,
  unary: 9,
  application: 10,
};

function commaSep1(rule) {
  return seq(rule, repeat(seq(',', rule)));
}

function commaSep(rule) {
  return optional(commaSep1(rule));
}

module.exports = grammar({
  name: 'fixed',

  extras: $ => [/[ \t\r\n\f]+/, $.comment],

  externals: $ => [$._indent, $._dedent, $._newline],

  word: $ => $.identifier,

  // `Self.fn ...` starts both a static method declaration and an ordinary
  // expression whose field is named `fn`; GLR resolves it on the `fn` token.
  conflicts: $ => [
    [$.use_path],
    [$.cap_decl],
    [$.data_decl],
    [$.lambda_param, $._atom],
    [$.of_arg, $._type_atom],
    [$.cap_bound],
    [$.of_arg],
    [$.effect_bound],
    [$.effect_row],
    [$.binary_expression, $.of_arg],
    [$.match_case],
  ],

  rules: {
    source_file: $ => repeat($._statement),

    comment: $ => token(seq('//', /[^\n]*/)),

    _statement: $ => choice(
      $.use_decl,
      $.using_decl,
      $.cap_decl,
      $.effect_decl,
      $.data_decl,
      $.type_decl,
      $.fn_decl,
      $.fn_decl_no_params,
      $.static_fn_decl,
      $.satisfies_decl,
      $.prop_decl,
      $.satisfies_mapping,
      $.do_bind,
      $._expression
    ),

    // ------------------------------------------------------------- imports

    use_decl: $ => seq('use', field('path', $.use_path), optional(choice($.use_tail, $.use_rename))),
    use_path: $ => seq(
      $.identifier,
      repeat(seq('.', choice($.identifier, $.type_identifier)))
    ),
    use_tail: $ => choice(
      seq('.', '{', commaSep1($.use_sel), optional(','), '}'),
      seq('satisfies', $.type_identifier)
    ),
    use_sel: $ => seq(field('name', choice($.identifier, $.type_identifier)), optional($.use_rename)),
    use_rename: $ => seq('as', field('alias', choice($.identifier, $.type_identifier))),

    using_decl: $ => seq('using', commaSep1($.type_identifier)),

    // -------------------------------------------------------- declarations

    cap_decl: $ => seq(
      optional('pub'),
      'cap',
      field('name', $.type_identifier),
      optional($.value_params),
      optional(seq('of', $.of_args)),
      optional(seq('extends', $.cap_bound_chain)),
      optional($.decl_block)
    ),

    effect_decl: $ => seq(
      optional('pub'),
      optional('linear'),
      'effect',
      field('name', $.type_identifier),
      optional(seq('of', $.of_args)),
      optional($.decl_block)
    ),

    data_decl: $ => seq(
      optional('pub'),
      'data',
      field('name', $.type_identifier),
      optional(seq('of', $.of_args)),
      optional($.data_body)
    ),
    data_body: $ => choice(
      seq('(', commaSep1($.field_decl), optional(','), ')'),
      seq(':', choice($.data_block, choice($.variant, $.prop_decl)))
    ),
    data_block: $ =>
      seq(
        $._indent,
        repeat1(choice($.variant, $.prop_decl, $._expression)),
        $._dedent
      ),
    field_decl: $ => seq(field('name', $.identifier), ':', field('type', $._type), optional(seq('=', $._expression))),
    variant: $ => prec(1, seq(
      field('name', $.type_identifier),
      '(',
      commaSep1($.field_decl),
      optional(','),
      ')'
    )),

    type_decl: $ => seq(
      optional('pub'),
      'type',
      field('name', $.type_identifier),
      optional($.value_params),
      '=',
      $._type
    ),

    fn_decl: $ => prec(1, seq(
      optional('pub'),
      optional($.fip_modifier),
      'fn',
      field('name', $.identifier),
      optional($.type_params),
      $.fn_params,
      optional(seq('->', $._return_type)),
      optional($.with_clause),
      optional(seq('=', $.block_tail))
    )),
    // Capability members and satisfies methods may omit the parameter list:
    // `fn head -> Part`, `fn top = self.head`. A separate rule keeps the
    // `fn name (` decision unambiguous.
    fn_decl_no_params: $ => seq(
      optional('pub'),
      optional($.fip_modifier),
      'fn',
      field('name', $.identifier),
      choice(
        seq('->', $._return_type, optional($.with_clause), optional(seq('=', $.block_tail))),
        seq($.with_clause, optional(seq('=', $.block_tail))),
        seq('=', $.block_tail)
      )
    ),
    // `Self.fn` is a static capability member. Kept separate from fn_decl
    // so `Self` does not force a GLR conflict with the `self` atom.
    static_fn_decl: $ => prec(1, seq(
      optional('pub'),
      optional($.fip_modifier),
      'Self',
      '.',
      'fn',
      field('name', $.identifier),
      optional($.type_params),
      choice(
        seq($.fn_params, optional(seq('->', $._return_type)), optional($.with_clause), optional(seq('=', $.block_tail))),
        seq('->', $._return_type, optional($.with_clause), optional(seq('=', $.block_tail))),
        seq($.with_clause, optional(seq('=', $.block_tail))),
        seq('=', $.block_tail)
      )
    )),
    fip_modifier: $ => seq('fip', optional(seq('(', choice('strict', $.number), ')'))),
    type_params: $ => seq('<', commaSep1($.type_identifier), optional(','), '>'),
    fn_params: $ => seq('(', optional(commaSep1($.fn_param)), optional(','), ')'),
    fn_param: $ => seq(field('name', $.identifier), ':', field('type', $._type), optional($.with_clause), optional(seq('=', $._expression))),
    _return_type: $ => choice(
      $._type,
      seq('cap', optional(choice(seq('of', $.of_args), seq('extends', $.cap_bound_chain))))
    ),
    with_clause: $ => seq('with', $.effect_row),
    effect_row: $ => seq($.effect_bound, repeat(seq('+', $.effect_bound))),
    effect_bound: $ => seq($.type_identifier, optional(seq('of', $.of_arg))),

    satisfies_decl: $ => seq(
      optional('pub'),
      field('subject', $.satisfies_subject),
      'satisfies',
      field('cap', $.type_identifier),
      optional(seq('of', $.of_arg)),
      optional(seq('as', $.type_identifier)),
      optional($.decl_block)
    ),
    satisfies_subject: $ => seq(
      choice($.type_identifier, $.identifier),
      optional(seq('of', 'Self'))
    ),
    satisfies_mapping: $ => seq(
      field('subject', choice($.type_identifier, 'Self', 'impossible')),
      'as',
      field('method', $.identifier)
    ),

    prop_decl: $ => seq(optional('assume'), 'prop', field('name', $.identifier), ':', $._expression),

    // ------------------------------------------------------------- blocks

    decl_block: $ => seq(':', choice($.block, $._statement)),
    block: $ => seq($._indent, repeat1($._statement), $._dedent),
    block_tail: $ => choice($._expression, $.block),

    // -------------------------------------------------------- expressions

    _expression: $ => choice(
      $.let_expr,
      $.forall_expr,
      $.binary_expression,
      $.unary_expression,
      $.ascription_expression,
      $.postfix_expression
    ),

    let_expr: $ => seq('let', field('pattern', $._pattern), '=', $._expression),

    forall_expr: $ => seq(
      'forall',
      '(',
      commaSep1($.binder),
      optional(','),
      ')',
      optional($.such_that),
      '->',
      $._expression
    ),
    binder: $ => seq(field('name', $.identifier), ':', field('type', $._type)),
    such_that: $ => seq('suchThat', $._expression),

    binary_expression: $ => choice(
      prec.left(PREC.pipe, seq($._expression, '|>', $._expression)),
      prec.right(PREC.implies, seq($._expression, 'implies', $._expression)),
      prec.left(PREC.or, seq($._expression, '||', $._expression)),
      prec.left(PREC.and, seq($._expression, '&&', $._expression)),
      prec.left(
        PREC.compare,
        seq($._expression, choice('==', '!=', '<', '>', '<=', '>='), $._expression)
      ),
      prec.left(PREC.add, seq($._expression, choice('+', '-'), $._expression)),
      prec.left(PREC.mul, seq($._expression, choice('*', '/', '%'), $._expression))
    ),
    unary_expression: $ => prec(PREC.unary, seq(choice('-', '!'), $.postfix_expression)),
    ascription_expression: $ =>
      prec.left(PREC.ascription, seq($._expression, 'is', $._type)),

    postfix_expression: $ =>
      prec.right(
        PREC.application,
        seq(
          $._atom,
          repeat(choice($.call_suffix, $.field_access)),
          optional($.using_clause)
        )
      ),
    call_suffix: $ => seq('(', optional(commaSep1($._expression)), optional(','), ')'),
    field_access: $ =>
      prec.right(
        seq('.', field('member', choice($.identifier, $.type_identifier)), optional($.call_suffix))
      ),
    using_clause: $ =>
      seq(
        'using',
        choice($.type_identifier, seq('(', commaSep1($.type_identifier), optional(','), ')'))
      ),

    _atom: $ => choice(
      $.identifier,
      $.type_identifier,
      $.number,
      $.string,
      $.char,
      $.boolean,
      $.unit,
      $.self,
      $.paren_expression,
      $.tuple_expression,
      $.list_expression,
      $.struct_literal,
      $.unreachable_expr,
      $.resume_expr,
      $.if_expr,
      $.match_expr,
      $.handle_expr,
      $.do_expr,
      $.lambda
    ),

    paren_expression: $ => seq('(', $._expression, ')'),
    tuple_expression: $ =>
      seq('(', $._expression, ',', optional(commaSep1($._expression)), optional(','), ')'),
    list_expression: $ => seq('[', optional(commaSep1($._expression)), optional(','), ']'),
    struct_literal: $ =>
      seq(
        field('type', $.type_identifier),
        '{',
        optional(commaSep1($.field_init)),
        optional(','),
        '}'
      ),
    field_init: $ => seq(field('name', $.identifier), optional(seq(':', $._expression))),

    lambda: $ => prec(2, seq($.lambda_params, '->', $.block_tail)),
    lambda_params: $ =>
      prec(1, seq('(', optional(commaSep1($.lambda_param)), optional(','), ')')),
    lambda_param: $ =>
      seq(field('name', $.identifier), optional(seq(':', $._type)), optional($.with_clause), optional(seq('=', $._expression))),

    if_expr: $ =>
      prec.right(
        seq(
          'if',
        $._expression,
        ':',
        $.block_tail,
          optional(seq('else', choice($.if_expr, seq(':', $.block_tail))))
        )
      ),

    match_expr: $ => seq('match', $._expression, ':', $.match_cases),
    match_cases: $ =>
      choice($.match_case, seq($._indent, repeat1($.match_case), $._dedent)),
    match_case: $ =>
      seq($.or_pattern, optional(seq('if', $._expression)), '=>', $.block_tail, optional($._newline)),
    or_pattern: $ => seq($._pattern, repeat(seq('|', $._pattern))),

    handle_expr: $ => seq('handle', $._expression, ':', $.handler_clauses),
    handler_clauses: $ =>
      choice($.handler_clause, seq($._indent, repeat1($.handler_clause), $._dedent)),
    handler_clause: $ =>
      choice(
        seq(
          $.type_identifier,
          '.',
          $.identifier,
          '(',
          optional(commaSep1($.handler_param)),
          optional(','),
          ')',
          '=>',
          $.block_tail
        ),
        seq('return', '(', $._pattern, ')', '=>', $.block_tail)
      ),
    handler_param: $ => seq($._pattern, optional(seq(':', $._type))),

    do_expr: $ => seq('do', ':', $.do_body),
    do_body: $ => choice($._expression, $.block),
    do_bind: $ => seq(optional('let'), field('name', $.identifier), '<-', $._expression),

    unreachable_expr: $ =>
      prec.right(seq('unreachable', optional(seq('(', $.string, ')')))),
    resume_expr: $ => seq('resume', '(', optional($._expression), ')'),

    // ----------------------------------------------------------- patterns

    _pattern: $ => choice(
      $.identifier,
      $.wildcard,
      $.number,
      $.string,
      $.char,
      $.boolean,
      $.unit,
      $.data_variant_pattern,
      $.tuple_pattern,
      $.list_pattern,
      $.struct_pattern
    ),
    wildcard: $ => '_',
    rest_pattern: $ => '..',
    data_variant_pattern: $ =>
      choice(
        seq(
          choice($.type_identifier, 'Self'),
          '.',
          $.type_identifier,
          optional($.pattern_list)
        ),
        seq($.type_identifier, optional($.pattern_list))
      ),
    pattern_list: $ => seq('(', optional(commaSep1($._pattern)), optional(','), ')'),
    tuple_pattern: $ =>
      seq('(', $._pattern, ',', optional(commaSep1($._pattern)), optional(','), ')'),
    list_pattern: $ =>
      seq(
        '[',
        optional(commaSep1(choice($._pattern, $.rest_pattern))),
        optional(','),
        ']'
      ),
    struct_pattern: $ =>
      seq(
        $.type_identifier,
        '{',
        optional(commaSep1($.field_pattern)),
        optional(seq(',', '..')),
        optional(','),
        '}'
      ),
    field_pattern: $ => seq(field('name', $.identifier), optional(seq(':', $._pattern))),

    // -------------------------------------------------------------- types

    _type: $ =>
      prec.right(
        seq(
          $._type_atom,
          repeat(
            choice(
              seq('->', $._type_atom, optional($.with_clause)),
              seq('of', $.of_args),
              seq('is', $.cap_bound_chain),
              seq('+', $.cap_bound),
              seq('using', $.using_clause)
            )
          )
        )
      ),
    _type_atom: $ =>
      choice(
        $.type_identifier,
        $.primitive_type,
        $.self,
        $.never_type,
        $.is_bound,
        $.unit,
        seq('(', commaSep1($._type), optional(','), ')')
      ),
    primitive_type: $ => $.identifier,
    never_type: $ => '!',
    is_bound: $ => seq('is', $.cap_bound_chain),
    cap_bound_chain: $ =>
      prec.left(seq($.cap_bound, repeat(seq('+', $.cap_bound)))),
    cap_bound: $ =>
      choice(
        $.refinement_call,
        prec.left(seq($.type_identifier, 'of', $.of_args)),
        $.type_identifier,
        $.primitive_type
      ),
    refinement_call: $ =>
      prec(1, seq(choice($.identifier, $.type_identifier), '(', optional($.args), ')')),
    of_args: $ => choice(
      prec(1, seq('(', commaSep1($.of_arg), optional(','), ')')),
      // An unparenthesised `of` argument cannot be a value-parameter
      // declaration: `effect Fail of E:` must stop at the body colon.
      $.of_simple_arg
    ),
    of_simple_arg: $ => choice($._type, $.number, $.string, $.char, $.boolean),
    of_arg: $ =>
      choice(
        prec(
          2,
          seq(
            choice($.identifier, $.type_identifier),
            ':',
            $._type,
            optional(seq('=', $._expression))
          )
        ),
        prec(2, seq(choice($.identifier, $.type_identifier), '=', $._expression)),
        $._type,
        $.number,
        $.string,
        $.char,
        $.boolean
      ),
    value_params: $ =>
      seq('(', optional(commaSep1($.value_param)), optional(','), ')'),
    value_param: $ =>
      seq(
        choice($.identifier, $.type_identifier),
        optional(seq(':', $._type)),
        optional(seq('=', $._expression))
      ),
    args: $ => commaSep1($._expression),

    // ------------------------------------------------------------- tokens

    identifier: $ => /[a-z_][A-Za-z0-9_]*/,
    type_identifier: $ => /[A-Z][A-Za-z0-9_]*/,
    number: $ =>
      token(
        choice(
          /\d[\d_]*\.\d[\d_]*(?:[eE][+-]?\d+)?/,
          /\d[\d_]*/
        )
      ),
    string: $ => token(seq('"', repeat(choice(/[^"\\\n]/, /\\./)), '"')),
    char: $ => token(seq("'", choice(/[^'\\\n]/, /\\./), "'")),
    boolean: $ => choice('true', 'false'),
    unit: $ => seq('(', ')'),
    self: $ => 'Self',
  },
});
