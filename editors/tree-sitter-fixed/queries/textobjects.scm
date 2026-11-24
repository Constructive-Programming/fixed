; Text objects for Neovim/Helix.
[
  (fn_decl)
  (fn_decl_no_params)
  (static_fn_decl)
] @function.outer

[
  (cap_decl)
  (data_decl)
  (effect_decl)
  (satisfies_decl)
  (type_decl)
] @class.outer

(fn_decl (block) @function.inner)
(fn_decl_no_params (block) @function.inner)
(static_fn_decl (block) @function.inner)

(fn_params) @parameter.outer
(fn_param) @parameter.inner
(lambda_params) @parameter.outer
(lambda_param) @parameter.inner

(block) @block.outer
(comment) @comment.outer
