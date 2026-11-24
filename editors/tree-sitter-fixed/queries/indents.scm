; Indent regions. Neovim uses @indent.begin/@indent.end; Helix uses
; @indent/@outdent. Both captures are attached so either editor can consume it.
[
  (block)
  (data_block)
  (match_cases)
  (handler_clauses)
  (do_body)
] @indent.begin @indent

["(" "[" "{"] @indent.begin @indent
[")" "]" "}"] @indent.end @outdent
