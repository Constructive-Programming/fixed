; Fixed tree-sitter locals.

(fn_decl) @local.scope
(fn_decl_no_params) @local.scope
(static_fn_decl) @local.scope
(lambda) @local.scope
(block) @local.scope

(fn_decl name: (identifier) @local.definition)
(fn_decl_no_params name: (identifier) @local.definition)
(static_fn_decl name: (identifier) @local.definition)
(fn_param name: (identifier) @local.definition)
(lambda_param name: (identifier) @local.definition)
(let_expr pattern: (identifier) @local.definition)
(do_bind name: (identifier) @local.definition)
(binder name: (identifier) @local.definition)

(identifier) @local.reference
