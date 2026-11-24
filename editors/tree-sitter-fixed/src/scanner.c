#include "tree_sitter/parser.h"

#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>

// Fixed uses Python-style meaningful indentation: a block is opened by a
// greater indent and closed by one or more dedents. The scanner emits only
// the two synthetic tokens; newlines are ordinary extras in grammar.js.
//
// The external token is zero-width (mark_end is called before skipping), so
// the same line's indentation is re-measured for each queued DEDENT.

enum TokenType {
  INDENT,
  DEDENT,
  NEWLINE,
};

#define MAX_INDENTS 128

typedef struct {
  uint16_t indents[MAX_INDENTS];
  uint8_t size;
} Scanner;

static inline void skip(TSLexer *lexer) { lexer->advance(lexer, true); }

void *tree_sitter_fixed_external_scanner_create(void) {
  Scanner *scanner = (Scanner *)calloc(1, sizeof(Scanner));
  scanner->indents[0] = 0;
  scanner->size = 1;
  return scanner;
}

void tree_sitter_fixed_external_scanner_destroy(void *payload) { free(payload); }

unsigned tree_sitter_fixed_external_scanner_serialize(void *payload, char *buffer) {
  Scanner *scanner = (Scanner *)payload;
  unsigned n = 0;
  buffer[n++] = (char)scanner->size;
  for (uint8_t i = 0; i < scanner->size; i++) {
    buffer[n++] = (char)(scanner->indents[i] & 0xFF);
    buffer[n++] = (char)((scanner->indents[i] >> 8) & 0xFF);
  }
  return n;
}

void tree_sitter_fixed_external_scanner_deserialize(
    void *payload,
    const char *buffer,
    unsigned length) {
  Scanner *scanner = (Scanner *)payload;
  scanner->size = 1;
  scanner->indents[0] = 0;
  if (length == 0) return;

  unsigned n = 0;
  uint8_t size = (uint8_t)buffer[n++];
  if (size > MAX_INDENTS) size = MAX_INDENTS;
  scanner->size = size == 0 ? 1 : size;

  for (uint8_t i = 0; i < scanner->size && n + 1 < length; i++) {
    scanner->indents[i] =
        (uint16_t)((uint8_t)buffer[n] | ((uint16_t)(uint8_t)buffer[n + 1] << 8));
    n += 2;
  }
}

bool tree_sitter_fixed_external_scanner_scan(
    void *payload,
    TSLexer *lexer,
    const bool *valid_symbols) {
  Scanner *scanner = (Scanner *)payload;

  if (!valid_symbols[INDENT] && !valid_symbols[DEDENT] && !valid_symbols[NEWLINE]) return false;

  // Zero-width token: the lexer re-scans this position for the next token.
  lexer->mark_end(lexer);

  bool saw_newline = false;
  uint32_t indent = 0;

  for (;;) {
    if (lexer->lookahead == ' ' || lexer->lookahead == '\t') {
      indent++;
      skip(lexer);
    } else if (lexer->lookahead == '\r') {
      skip(lexer);
    } else if (lexer->lookahead == '\n') {
      saw_newline = true;
      indent = 0;
      skip(lexer);
    } else if (lexer->lookahead == '/') {
      skip(lexer);
      if (lexer->lookahead == '/') {
        while (lexer->lookahead && lexer->lookahead != '\n') skip(lexer);
        saw_newline = true;
        indent = 0;
      } else {
        // A line cannot begin with `/` in Fixed; let the parser recover.
        return false;
      }
    } else {
      break;
    }
  }

  if (lexer->eof(lexer)) {
    if (scanner->size > 1 && valid_symbols[DEDENT]) {
      scanner->size--;
      lexer->result_symbol = DEDENT;
      return true;
    }
    return false;
  }

  // Spec A.3: an off-side body re-engaged inside brackets terminates at a
  // token the enclosing bracketed production is waiting for. Emit a
  // zero-width DEDENT without consuming the delimiter, so the enclosing
  // list/tuple/call still sees its comma or closing bracket.
  if (valid_symbols[DEDENT] && scanner->size > 1 &&
      (lexer->lookahead == ',' || lexer->lookahead == ')' ||
       lexer->lookahead == ']' || lexer->lookahead == '}')) {
    scanner->size--;
    lexer->result_symbol = DEDENT;
    return true;
  }

  if (!saw_newline) return false;

  uint16_t current = scanner->indents[scanner->size - 1];

  if (indent > current && valid_symbols[INDENT]) {
    if (scanner->size < MAX_INDENTS) {
      scanner->indents[scanner->size++] = (uint16_t)indent;
    }
    lexer->result_symbol = INDENT;
    return true;
  }

  if (indent < current && valid_symbols[DEDENT]) {
    scanner->size--;
    lexer->result_symbol = DEDENT;
    return true;
  }

  if (valid_symbols[NEWLINE]) {
    // Leading continuation tokens keep the logical line open.
    int32_t next = lexer->lookahead;
    if (next == '.' || next == '|' || next == '+' || next == 'w' || next == 'e') {
      return false;
    }
    lexer->result_symbol = NEWLINE;
    return true;
  }

  return false;
}
