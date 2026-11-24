; Fixed language highlights for Zed.
; Zed uses a smaller capture set than Neovim/Helix, so subcaptures are folded
; into the base captures (`@keyword`, `@function`, `@type`, ...).

(identifier) @variable
(type_identifier) @type
(primitive_type) @type.builtin
(number) @number
(string) @string
(char) @string
(boolean) @boolean
(unit) @constant.builtin
(never_type) @type.builtin
(self) @variable.special
(comment) @comment

; Keywords
["if" "else" "match"] @keyword
["handle" "resume" "return" "unreachable" "impossible"] @keyword
["do" "let" "forall" "suchThat" "implies" "assume" "strict"] @keyword
["is" "of" "with" "as" "extends"] @keyword
"fn" @keyword
["cap" "data" "effect" "type" "prop" "satisfies"] @keyword
["pub" "linear" "fip"] @keyword
["use" "using"] @keyword

; Declarations
(fn_decl name: (identifier) @function)
(fn_decl_no_params name: (identifier) @function)
(static_fn_decl name: (identifier) @function)
(cap_decl name: (type_identifier) @type)
(data_decl name: (type_identifier) @type)
(effect_decl name: (type_identifier) @type)
(type_decl name: (type_identifier) @type)
(prop_decl name: (identifier) @function)
(satisfies_decl cap: (type_identifier) @type)
(satisfies_decl subject: (satisfies_subject (type_identifier) @type))
(satisfies_mapping method: (identifier) @function)
(variant name: (type_identifier) @variant)

; Imports
(use_path (identifier) @variable)
(use_path (type_identifier) @type)
(use_sel name: (_) @variable)
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
(postfix_expression . (identifier) @function (call_suffix))
(field_access member: (identifier) @function (call_suffix))
(struct_literal type: (type_identifier) @constructor)
(data_variant_pattern (type_identifier) @constructor)

; Operators and punctuation
[
  "->" "=>" "|>" "<-" "==" "!=" "<=" ">=" "&&" "||"
  "+" "-" "*" "/" "%" "=" "<" ">" "|" "!"
] @operator

["," ":" "."] @punctuation.delimiter
["(" ")" "[" "]" "{" "}"] @punctuation.bracket
