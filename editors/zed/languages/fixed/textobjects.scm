[
  (fn_decl)
  (fn_decl_no_params)
  (static_fn_decl)
] @function.around

(fn_decl (block) @function.inside)
(fn_decl_no_params (block) @function.inside)
(static_fn_decl (block) @function.inside)

[
  (cap_decl)
  (data_decl)
  (effect_decl)
  (satisfies_decl)
  (type_decl)
] @class.around

(comment) @comment.around
