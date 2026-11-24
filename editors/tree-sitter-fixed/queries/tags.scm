; Symbol tags for tree-sitter tags / editor symbol navigation.
(fn_decl name: (identifier) @name) @definition.function
(fn_decl_no_params name: (identifier) @name) @definition.function
(static_fn_decl name: (identifier) @name) @definition.method
(cap_decl name: (type_identifier) @name) @definition.class
(data_decl name: (type_identifier) @name) @definition.class
(effect_decl name: (type_identifier) @name) @definition.class
(type_decl name: (type_identifier) @name) @definition.type
(prop_decl name: (identifier) @name) @definition.function

(fn_param name: (identifier) @name) @definition.parameter
(lambda_param name: (identifier) @name) @definition.parameter
(binder name: (identifier) @name) @definition.parameter
(let_expr pattern: (identifier) @name) @definition.var
(do_bind name: (identifier) @name) @definition.var

(postfix_expression . (identifier) @name (call_suffix)) @reference.call
(field_access member: (identifier) @name (call_suffix)) @reference.call
(use_path (identifier) @name) @reference.module
(use_sel name: (_) @name) @reference.module
