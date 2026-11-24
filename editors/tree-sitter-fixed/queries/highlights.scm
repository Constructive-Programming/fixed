; Fixed tree-sitter highlights.
; Generic captures first; later, more specific captures win in Neovim and Helix.
;
; Contextual words are real tokens only where the grammar accepts them:
; `is`/`of`/`with`/`as`/`extends` in type and clause positions, `using` in a
; declaration or a postfix clause, `assume` before `prop`, `strict` inside
; `fip(...)`. Where they are ordinary identifiers the lexer emits `identifier`.

(identifier) @variable
(type_identifier) @type
(primitive_type) @type.builtin
(number) @number
(string) @string
(char) @character
(boolean) @boolean
(unit) @constant.builtin
(never_type) @type.builtin
(self) @variable.builtin
(comment) @comment @spell

; Keywords
["if" "else" "match"] @keyword.conditional
["handle" "resume" "return" "unreachable" "impossible"] @keyword.exception
["do" "let" "forall" "suchThat" "implies" "assume" "strict"] @keyword
["is" "of" "with" "as" "extends"] @keyword.operator
"fn" @keyword.function
["cap" "data" "effect" "type" "prop" "satisfies"] @keyword
["pub" "linear" "fip"] @storage.modifier
["use" "using"] @keyword.import

; Declarations
(fn_decl name: (identifier) @function)
(fn_decl_no_params name: (identifier) @function)
(static_fn_decl name: (identifier) @function.method)
(cap_decl name: (type_identifier) @type)
(data_decl name: (type_identifier) @type)
(effect_decl name: (type_identifier) @type)
(type_decl name: (type_identifier) @type)
(prop_decl name: (identifier) @function)
(satisfies_decl cap: (type_identifier) @type)
(satisfies_decl subject: (satisfies_subject (type_identifier) @type))
(satisfies_mapping method: (identifier) @function.method)
(variant name: (type_identifier) @constructor)

; Imports
(use_path (identifier) @namespace)
(use_path (type_identifier) @type)
(use_sel name: (_) @namespace)
(use_rename alias: (_) @type)

; Parameters and fields
(fn_param name: (identifier) @variable.parameter)
(lambda_param name: (identifier) @variable.parameter)
(binder name: (identifier) @variable.parameter)
(handler_param (identifier) @variable.parameter)
(field_decl name: (identifier) @property)
(field_init name: (identifier) @property)
(field_pattern name: (identifier) @property)
(field_access member: (_) @property)

; Calls and constructors
(postfix_expression . (identifier) @function.call (call_suffix))
(field_access member: (identifier) @function.method (call_suffix))
(struct_literal type: (type_identifier) @constructor)
(data_variant_pattern (type_identifier) @constructor)

; Operators and punctuation
[
  "->"
  "=>"
  "|>"
  "<-"
  "=="
  "!="
  "<="
  ">="
  "&&"
  "||"
  "+"
  "-"
  "*"
  "/"
  "%"
  "="
  "<"
  ">"
  "|"
  "!"
] @operator

["," ":" "."] @punctuation.delimiter
["(" ")" "[" "]" "{" "}"] @punctuation.bracket
