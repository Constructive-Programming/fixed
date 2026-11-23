/* Fixed runtime for the Phase 4.0 backend slice (docs/plans/phase-4.0-backend-slice.md §4.7).
 *
 * Linked by `clang` with the LLVM IR the compiler emits; the compiler itself emits only LLVM IR.
 * Provides allocation, reference counting, strings, the console, scalar formatting and the
 * counters behind the baseline. Calling convention: every function consumes the boxed values
 * passed to it.
 *
 * Heap object layout (spec/memory_management.md Rule PR3.1): a 16-byte header, then the payload,
 * one 64-bit slot per field in declaration order. The header's reference map `refs` has bit i set
 * when payload slot i holds a counted reference, so one generic routine can drop any object
 * whatever type instance built it. Only slots 0 to 31 can hold references; the compiler rejects a
 * cell with a reference beyond them. A negative count marks a static object (string constants),
 * which is never counted or freed.
 */
#define _POSIX_C_SOURCE 200809L
#include <inttypes.h>
#include <math.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <time.h>

typedef struct fx_header {
    int64_t rc;
    uint32_t tag;
    uint32_t refs;
} fx_header;

typedef struct fx_string {
    fx_header h;
    int64_t len;
    char bytes[];
} fx_string;

/* The counters behind the baselines and the tests' leak checks (docs/plans/phase-4.0-backend-slice.md
   §4.7, docs/baselines/runtime-2026-11-23-runtime.md §1), compiled in only
   with FX_STATS (`fixed ship --stats`, test builds): STAT(e) evaluates `e` only then. Without them a
   run with FIXED_RT_STATS set reports time and memory and says the counters are missing. */
#ifdef FX_STATS
#define STAT(e) (e)
static uint64_t n_alloc, n_free, n_dup, n_drop, n_wop; /* n_wop: dup and drop calls through a value witness */
/* Reuse sites: taken without a test (exclusive), and `rc == 1` tests passed (reused) or failed (dropped). */
static uint64_t n_reuse_excl, n_test_pass, n_test_fail;
static uint64_t n_frame_alloc, n_frame_free, n_frame_bytes; /* frame cell bytes, check words excluded */
#else
#define STAT(e) ((void)0)
#endif
static struct timespec t_start;

static void fx_die(const char *msg, int code) {
    fflush(stdout);
    fprintf(stderr, "fixed: %s\n", msg);
    exit(code);
}

/* Checked builds put a check word in front of every frame cell (the data stack below): minus the
   size of a live cell with a count word, FRAME_BARE minus the size of a live cell without one, and
   FRAME_DEAD minus the size of a released cell, or of the dead run it starts. Sizes include the
   check word and are below 2^40, so the three ranges do not meet. */
#define FRAME_DEAD (-((int64_t)1 << 40))
#define FRAME_BARE (-((int64_t)1 << 50))

#ifdef FX_CHECK_FRAME
static bool on_frame_stack(const void *p);
#endif

/* Stop on a count operation that reaches a frame cell whose scope was reset, or one without a
   count word (checked builds). Any other frame cell holds a negative count, which the caller
   leaves alone. */
static inline void frame_live(const fx_header *h) {
#ifdef FX_CHECK_FRAME
    if (!on_frame_stack(h)) return;
    int64_t check = ((const int64_t *)h)[-1];
    if (check <= FRAME_BARE) fx_die("internal error: a count operation on a frame cell without a count word", 103);
    if (check <= FRAME_DEAD) fx_die("internal error: a frame cell used after its scope ended", 103);
#else
    (void)h;
#endif
}

void *fx_alloc(uint32_t tag, uint32_t refs, size_t payload) {
    fx_header *h = malloc(sizeof(fx_header) + payload);
    if (!h) fx_die("out of memory", 102);
    h->rc = 1;
    h->tag = tag;
    h->refs = refs;
    STAT(n_alloc++);
    return h;
}

void fx_drop(fx_header *h);

/* Drop every counted reference in `h`'s payload, as its reference map lists them. */
static void drop_children(fx_header *h) {
    fx_header **slots = (fx_header **)(h + 1);
    for (uint32_t refs = h->refs; refs; refs &= refs - 1) fx_drop(slots[__builtin_ctz(refs)]);
}

/* Reuse (spec/memory_management.md §11), drop guided: the token is taken where the cell would
   have been dropped. The cell's children are dropped (lowering duplicated the ones it read, so
   those return to the count they had), and the empty cell (refs 0) is the token: a constructor
   fills it, or, if none does, it is dropped like any value. */
static fx_header *reclaim(fx_header *h) {
    drop_children(h);
    h->refs = 0;
    return h;
}

/* Exclusively owned: no test. FX_CHECK_EXCL (test builds) verifies the claim. */
void *fx_reuse_excl(fx_header *h) {
    frame_live(h);
#ifdef FX_CHECK_EXCL
    if (h->rc != 1) fx_die("internal error: reused a cell that is not exclusively owned", 103);
#endif
    STAT(n_reuse_excl++);
    return reclaim(h);
}

/* Shared: Koka's path. Reuse when the count says no one else holds it; else release our share. */
void *fx_reuse(fx_header *h) {
    frame_live(h);
    if (h->rc == 1) {
        STAT(n_test_pass++);
        return reclaim(h);
    }
    STAT(n_test_fail++);
    fx_drop(h);
    return NULL;
}

/* A cell for a constructor: the reuse token if there is one, else a fresh allocation. */
void *fx_alloc_at(void *token, uint32_t tag, uint32_t refs, size_t payload) {
    if (!token) return fx_alloc(tag, refs, payload);
    fx_header *h = token;
    h->rc = 1;
    h->tag = tag;
    h->refs = refs;
    return h;
}

void fx_dup(fx_header *h) {
    if (!h) return; /* the zero result of an aborted call */
    frame_live(h);
    STAT(n_dup++);
    if (h->rc > 0) h->rc++;
}

/* Free a cell whose count reached zero, and its children, in constant native stack (Rule PR7.3).
   Continues on the highest marked slot, so a list is freed in a loop; another child whose count
   reaches zero waits on `pending`, linked through its dead count field. `pending` is empty on return
   and free_cell never re-enters, so it can be static (saves a register across `free`). Out of line:
   inlined into fx_drop it cost 6-8% (docs/baselines/runtime-2026-11-20-phase-4.1.md). */
static fx_header *pending;
static __attribute__((noinline)) void free_cell(fx_header *h) {
    for (;;) {
        fx_header **slots = (fx_header **)(h + 1);
        uint32_t refs = h->refs;
        fx_header *last = NULL;
        if (refs) {
            uint32_t hi = 31 - (uint32_t)__builtin_clz(refs);
            last = slots[hi];
            for (refs &= ~(1u << hi); refs; refs &= refs - 1) {
                fx_header *c = slots[__builtin_ctz(refs)];
                if (!c) continue;
                frame_live(c);
                STAT(n_drop++);
                if (c->rc < 0 || --c->rc != 0) continue;
                c->rc = (int64_t)(intptr_t)pending;
                pending = c;
            }
        }
        free(h);
        STAT(n_free++);
        if (last) {
            frame_live(last);
            STAT(n_drop++);
            if (last->rc >= 0 && --last->rc == 0) {
                h = last;
                continue;
            }
        }
        if (!pending) return;
        h = pending;
        pending = (fx_header *)(intptr_t)h->rc;
    }
}

void fx_drop(fx_header *h) {
    if (!h) return;
    frame_live(h);
    STAT(n_drop++);
    if (h->rc < 0 || --h->rc != 0) return;
    free_cell(h);
}

void fx_panic_div(void) { fx_die("panic: integer division by zero", 101); }

/* ---- the data stack (spec/memory_management.md Rules MM6.1, MM6.2) ----

   The `Frame` cells of a function activation are bumped above the mark it takes on entry and are
   freed together when it resets the stack to that mark on exit. A list of chunks, the first 4 KiB
   and each new one twice the last; a mark is the top pointer, whose chunk a reset finds by walking
   back. Chunks above the top are kept for reuse. ponytail: chunks are not aligned to their size (Rule MM6.1) because
   nothing masks a pointer to find its chunk yet; align them when a region descriptor is needed.

   A frame cell from `fx_frame_alloc` has the header of every cell, its count word holding Rule
   PR3.1's immortal sentinel, as a static cell's does, so `fx_dup`, `fx_drop`, `fx_reuse` and the
   drop of a parent leave it alone, and code that does not know a value's class cannot free or count
   it. Only `fx_reuse_excl` must never see one: the compiler takes no reuse token from a frame
   value, and FX_CHECK_EXCL stops on one. A cell from `fx_frame_alloc_bare` has the descriptor and
   no count word (Rule MM3.1): 8 bytes, then the payload. The compiler asks for one only where
   every use of the cell is code compiled for that layout, which counts nothing.

   FX_CHECK_FRAME (test builds): every frame cell has a check word in front of it (FRAME_DEAD
   above), which the program never sees. A reset does not rewind; it counts the cells it releases
   and poisons them (check word below FRAME_DEAD, the cell 0xA5), so a frame cell reached after its
   scope ended reads poison. A count operation on a released cell, or on any cell without a count
   word, stops the program. The first check word of each poisoned run holds the run's length, so a
   later reset over it steps across it at once: a loop that resets to one mark at every step walks
   each cell once. */

typedef struct fx_chunk {
    struct fx_chunk *prev, *next;
    char *limit, *used; /* end of the chunk; where allocation stopped when it moved to the next */
    char data[];
} fx_chunk;

char *fx_frame_top;
static char *frame_limit;
static fx_chunk *frame_chunk;

/* Continue in the next chunk, one that holds at least `n` bytes. */
static void frame_next(size_t n) {
    fx_chunk *c = frame_chunk ? frame_chunk->next : NULL;
    if (!c || (size_t)(c->limit - c->data) < n) {
        size_t size = frame_chunk ? 2 * (size_t)(frame_chunk->limit - frame_chunk->data) : 4096;
        while (size < n) size *= 2;
        for (fx_chunk *old = c, *up; old; old = up) { up = old->next; free(old); }
        c = malloc(sizeof *c + size);
        if (!c) fx_die("out of memory", 102);
        c->prev = frame_chunk;
        c->next = NULL;
        c->limit = c->data + size;
        if (frame_chunk) frame_chunk->next = c;
    }
    if (frame_chunk) frame_chunk->used = fx_frame_top;
    frame_chunk = c;
    fx_frame_top = c->data;
    frame_limit = c->limit;
}

#ifdef FX_CHECK_FRAME
#define CHECK_WORD 8
#else
#define CHECK_WORD 0
#endif

/* A cell of `n` bytes, rounded up to 8, on the data stack, after the check word of a checked build,
   which holds `check` minus the size taken. */
static char *frame_bump(size_t n, int64_t check) {
    n = (n + 7) & ~(size_t)7;
    size_t taken = CHECK_WORD + n;
    if ((size_t)(frame_limit - fx_frame_top) < taken) frame_next(taken);
    char *p = fx_frame_top + CHECK_WORD;
#ifdef FX_CHECK_FRAME
    *(int64_t *)fx_frame_top = check - (int64_t)taken;
#else
    (void)check;
#endif
    fx_frame_top += taken;
    STAT(n_frame_alloc++);
    STAT(n_frame_bytes += n);
    return p;
}

void *fx_frame_alloc(uint32_t tag, uint32_t refs, size_t payload) {
    fx_header *h = (fx_header *)frame_bump(sizeof(fx_header) + payload, 0);
    h->rc = -1;
    h->tag = tag;
    h->refs = refs;
    return h;
}

/* A frame cell without a count word: its descriptor (`tag`, `refs`), then the payload. */
void *fx_frame_alloc_bare(uint32_t tag, uint32_t refs, size_t payload) {
    uint32_t *d = (uint32_t *)frame_bump(2 * sizeof(uint32_t) + payload, FRAME_BARE);
    d[0] = tag;
    d[1] = refs;
    return d;
}

static bool in_chunk(const fx_chunk *c, const char *p) {
    return (uintptr_t)p >= (uintptr_t)c->data && (uintptr_t)p <= (uintptr_t)c->limit;
}

#ifdef FX_CHECK_FRAME
/* A checked build never rewinds, so every chunk holding a cell is the current one or before it. */
static bool on_frame_stack(const void *p) {
    for (const fx_chunk *c = frame_chunk; c; c = c->prev)
        if (in_chunk(c, p)) return true;
    return false;
}

/* Poison the live cells from `from` to `end`, and make the whole range one dead run. */
static void frame_poison(char *from, char *end) {
    for (char *p = from; p < end;) {
        int64_t *check = (int64_t *)p;
        if (*check >= 0) fx_die("internal error: a cell on the data stack without a check word", 103);
        bool dead = *check <= FRAME_DEAD && *check > FRAME_BARE;
        int64_t base = dead ? FRAME_DEAD : *check <= FRAME_BARE ? FRAME_BARE : 0;
        int64_t size = base - *check;
        if (!dead) {
            STAT(n_frame_free++);
            memset(check + 1, 0xA5, (size_t)size - CHECK_WORD);
            *check = FRAME_DEAD - size;
        }
        p += size;
    }
    if (from < end) *(int64_t *)from = FRAME_DEAD - (end - from);
}
#endif

void fx_frame_reset(char *mark) {
#ifdef FX_CHECK_FRAME
    fx_chunk *c = frame_chunk;
    while (c && !in_chunk(c, mark)) c = c->prev;
    if (!c || (c == frame_chunk && mark > fx_frame_top))
        fx_die("internal error: a mark not on the data stack", 103);
    for (char *from = mark; c; c = c->next, from = c ? c->data : NULL)
        frame_poison(from, c == frame_chunk ? fx_frame_top : c->used);
#else
    while (!in_chunk(frame_chunk, mark)) frame_chunk = frame_chunk->prev;
    fx_frame_top = mark;
    frame_limit = frame_chunk->limit;
#endif
}

/* ---- value witnesses (spec/compilation_model.md Rule CM4.1, spec/memory_management.md Rule MM7.3) ----

   A value witness record describes how to handle a 64-bit slot of a type the code cannot see.
   Fields are only ever appended, so emitted code reads each at a fixed offset: `kind` at 0 (1 when
   the slot holds a counted reference, so that it is the slot's reference-map bit), then `dup` at 8,
   `drop` at 16 and `copy` at 24. `reuse_test`, `evacuate` and `size` have no consumer before region
   lowering and are not here yet. Every reference is on the heap, so two witnesses give `kind`, `dup`
   and `drop` for every type: one for immediates and one for heap references. */

typedef struct fx_witness {
    int64_t kind;
    void (*dup)(int64_t);
    void (*drop)(int64_t);
    int64_t (*copy)(int64_t);
} fx_witness;

static void imm_keep(int64_t v) { (void)v; STAT(n_wop++); }
static int64_t imm_copy(int64_t v) { return v; }
static void ref_dup(int64_t v) { STAT(n_wop++); fx_dup((fx_header *)(intptr_t)v); }
static void ref_drop(int64_t v) { STAT(n_wop++); fx_drop((fx_header *)(intptr_t)v); }

/* A cell's header gives neither its size nor its field types, so one witness for every reference
   type cannot copy one. Copying needs a witness per type, whose `copy` is that type's copy
   function; nothing asks for a copy of a value of parameter type yet
   (docs/plans/phase-4.1-baseline-specialiser.md, 4.1.M5). */
static int64_t ref_copy(int64_t v) {
    (void)v;
    fx_die("internal error: no copy of a reference of unknown type", 103);
    return 0;
}

const fx_witness fx_w_imm = {0, imm_keep, imm_keep, imm_copy};
const fx_witness fx_w_ref = {1, ref_dup, ref_drop, ref_copy};

/* ---- capability tables built at run time (spec/compilation_model.md Rule CM4.2) ---- */

/* The table of a capability at a type whose arguments are known only at run time (a type that
   grows under polymorphic recursion, `Pair of (T, T)` in a function generic in `T`): the `entries`
   code pointer pairs of the static `layout`, then the `n` witnesses and tables of its arguments,
   which the entries read. Rule CM4.2 builds such a record on the stack of the frame that forms it;
   here it is interned on the heap instead, because a closure that captures it may outlive that
   frame, and a record on the stack of a self call that is a loop would grow the stack. Equal
   contents give the same table, so each type a run reaches has one table, kept until exit: as many
   as the static tables an enumeration at compile time would need, which cannot be done for a type
   that grows. The compiler keeps `n` at most 64 (`Tables.maxCaptures`). ponytail: an open-
   addressing table with linear probing that doubles at half full, never freed; free tables with
   the frames that form them if a run reaches too many types. */
typedef struct fx_table_key {
    const void *layout;
    int64_t entries, n;
    void **table;
} fx_table_key;

static fx_table_key *tables;
static size_t tables_cap, tables_len;

static size_t table_hash(const void *layout, int64_t n, void *const *args) {
    size_t h = (size_t)(uintptr_t)layout * 0x9E3779B97F4A7C15u;
    for (int64_t i = 0; i < n; i++) h = (h ^ (size_t)(uintptr_t)args[i]) * 0x100000001B3u;
    return h;
}

static bool table_is(const fx_table_key *k, const void *layout, int64_t n, void *const *args) {
    return k->layout == layout && k->n == n && memcmp(k->table + 2 * k->entries, args, (size_t)n * sizeof(void *)) == 0;
}

void *fx_table(const void *layout, int64_t entries, int64_t n, ...) {
    void *args[64];
    if (n > 64) fx_die("internal error: a table built at run time with more than 64 arguments", 103);
    va_list ap;
    va_start(ap, n);
    for (int64_t i = 0; i < n; i++) args[i] = va_arg(ap, void *);
    va_end(ap);
    if (2 * (tables_len + 1) > tables_cap) {
        size_t cap = tables_cap ? 2 * tables_cap : 64;
        fx_table_key *grown = calloc(cap, sizeof *grown);
        if (!grown) fx_die("out of memory", 102);
        for (size_t i = 0; i < tables_cap; i++)
            if (tables[i].layout) {
                size_t j = table_hash(tables[i].layout, tables[i].n, tables[i].table + 2 * tables[i].entries) & (cap - 1);
                while (grown[j].layout) j = (j + 1) & (cap - 1);
                grown[j] = tables[i];
            }
        free(tables);
        tables = grown;
        tables_cap = cap;
    }
    size_t i = table_hash(layout, n, args) & (tables_cap - 1);
    for (; tables[i].layout; i = (i + 1) & (tables_cap - 1))
        if (table_is(&tables[i], layout, n, args)) return tables[i].table;
    void **t = malloc((size_t)(2 * entries + n) * sizeof(void *));
    if (!t) fx_die("out of memory", 102);
    memcpy(t, layout, (size_t)(2 * entries) * sizeof(void *));
    memcpy(t + 2 * entries, args, (size_t)n * sizeof(void *));
    tables[i] = (fx_table_key){layout, entries, n, t};
    tables_len++;
    return t;
}

/* ---- checked abort for abortive effect operations (slice §4.6) ---- */

static bool aborting;
static int64_t abort_effect;
static void *abort_payload;

void fx_abort(int64_t effect, void *payload) {
    aborting = true;
    abort_effect = effect;
    abort_payload = payload;
}

bool fx_aborting(void) { return aborting; }

/* True, and the abort cleared, if the program is aborting with `effect`. */
bool fx_abort_catch(int64_t effect) {
    if (!aborting || abort_effect != effect) return false;
    aborting = false;
    return true;
}

/* The caught payload; ownership passes to the handler clause. */
void *fx_abort_payload(void) {
    void *p = abort_payload;
    abort_payload = NULL;
    return p;
}

/* ---- strings ---- */

static fx_string *str_new(int64_t len) {
    fx_string *s = fx_alloc(0, 0, sizeof(int64_t) + (size_t)len + 1);
    s->len = len;
    s->bytes[len] = 0;
    return s;
}

static fx_string *str_of(const char *c, int64_t len) {
    fx_string *s = str_new(len);
    memcpy(s->bytes, c, (size_t)len);
    return s;
}

fx_string *fx_str_concat(fx_string *a, fx_string *b) {
    fx_string *s = str_new(a->len + b->len);
    memcpy(s->bytes, a->bytes, (size_t)a->len);
    memcpy(s->bytes + a->len, b->bytes, (size_t)b->len);
    fx_drop(&a->h);
    fx_drop(&b->h);
    return s;
}

bool fx_str_eq(fx_string *a, fx_string *b) {
    bool eq = a->len == b->len && memcmp(a->bytes, b->bytes, (size_t)a->len) == 0;
    fx_drop(&a->h);
    fx_drop(&b->h);
    return eq;
}

/* The order of two strings: negative, zero or positive as `a` is before, equal to or after `b`,
 * comparing bytes as unsigned values and a prefix before the longer string. */
int32_t fx_str_cmp(fx_string *a, fx_string *b) {
    int64_t n = a->len < b->len ? a->len : b->len;
    int c = n > 0 ? memcmp(a->bytes, b->bytes, (size_t)n) : 0;
    int32_t order = c != 0 ? (c < 0 ? -1 : 1) : (a->len < b->len ? -1 : a->len > b->len ? 1 : 0);
    fx_drop(&a->h);
    fx_drop(&b->h);
    return order;
}

/* ---- console (Rule E5.4 runtime effects) ---- */

void fx_print_line(fx_string *s) {
    fwrite(s->bytes, 1, (size_t)s->len, stdout);
    fputc('\n', stdout);
    fx_drop(&s->h);
}

fx_string *fx_read_line(void) {
    char *line = NULL;
    size_t cap = 0;
    ssize_t n = getline(&line, &cap, stdin);
    if (n < 0) n = 0;
    while (n > 0 && (line[n - 1] == '\n' || line[n - 1] == '\r')) n--;
    fx_string *s = str_of(line ? line : "", n);
    free(line);
    return s;
}

/* ---- parsing (builtin.parseU64) ---- */

static bool parse_ok;

uint64_t fx_parse_u64(fx_string *s) {
    char *end = NULL;
    uint64_t v = 0;
    parse_ok = s->len > 0 && s->bytes[0] >= '0' && s->bytes[0] <= '9';
    if (parse_ok) {
        v = strtoull(s->bytes, &end, 10);
        parse_ok = end == s->bytes + s->len;
    }
    fx_drop(&s->h);
    return parse_ok ? v : 0;
}

bool fx_parse_ok(void) { return parse_ok; }

/* ---- scalar formatting ---- */

fx_string *fx_show_i64(int64_t v) {
    char buf[32];
    return str_of(buf, snprintf(buf, sizeof buf, "%" PRId64, v));
}

fx_string *fx_show_u64(uint64_t v) {
    char buf[32];
    return str_of(buf, snprintf(buf, sizeof buf, "%" PRIu64, v));
}

fx_string *fx_show_bool(bool v) { return v ? str_of("true", 4) : str_of("false", 5); }

/* The shortest decimal that reads back as the same double. Plain notation with at least one
 * decimal (`10.0`, `9.58`, `0.3333333333333333`) for exponents -4..15, scientific otherwise
 * (`1e+300`). Format to be written into spec/stdlib.md at 4.0.M3 (slice §7.7). */
fx_string *fx_show_f64(double v) {
    char buf[40];
    if (!isfinite(v)) return str_of(buf, snprintf(buf, sizeof buf, isnan(v) ? "NaN" : v > 0 ? "inf" : "-inf"));
    int prec = 1;
    for (; prec < 17; prec++) {
        snprintf(buf, sizeof buf, "%.*e", prec - 1, v);
        if (strtod(buf, NULL) == v) break;
    }
    snprintf(buf, sizeof buf, "%.*e", prec - 1, v);
    int exp10 = (int)strtol(strchr(buf, 'e') + 1, NULL, 10);
    if (exp10 < -4 || exp10 > 15) return str_of(buf, (int64_t)strlen(buf));
    int decimals = prec - 1 - exp10 < 1 ? 1 : prec - 1 - exp10;
    return str_of(buf, snprintf(buf, sizeof buf, "%.*f", decimals, v));
}

fx_string *fx_show_char(uint32_t c) {
    char b[4];
    int n;
    if (c < 0x80) { b[0] = (char)c; n = 1; }
    else if (c < 0x800) { b[0] = (char)(0xC0 | c >> 6); b[1] = (char)(0x80 | (c & 0x3F)); n = 2; }
    else if (c < 0x10000) {
        b[0] = (char)(0xE0 | c >> 12); b[1] = (char)(0x80 | (c >> 6 & 0x3F)); b[2] = (char)(0x80 | (c & 0x3F)); n = 3;
    } else {
        b[0] = (char)(0xF0 | c >> 18); b[1] = (char)(0x80 | (c >> 12 & 0x3F));
        b[2] = (char)(0x80 | (c >> 6 & 0x3F)); b[3] = (char)(0x80 | (c & 0x3F)); n = 4;
    }
    return str_of(b, n);
}

/* ---- entry point and counters ---- */

extern int8_t fx_u_main(void);

int main(void) {
    clock_gettime(CLOCK_MONOTONIC, &t_start);
    frame_next(0);
#if defined(FX_STATS) && !defined(FX_CHECK_FRAME)
    char *bottom = fx_frame_top;
#endif
    fx_u_main();
    fflush(stdout);
#if defined(FX_STATS) && !defined(FX_CHECK_FRAME)
    /* Unchecked, a reset does not count cells: every one was released if the stack is back at its bottom. */
    if (frame_chunk->prev == NULL && fx_frame_top == bottom) n_frame_free = n_frame_alloc;
#endif
    if (getenv("FIXED_RT_STATS")) {
        struct timespec t_end;
        struct rusage ru;
        clock_gettime(CLOCK_MONOTONIC, &t_end);
        getrusage(RUSAGE_SELF, &ru);
        int64_t wall_ns = (t_end.tv_sec - t_start.tv_sec) * 1000000000LL + (t_end.tv_nsec - t_start.tv_nsec);
#ifdef FX_STATS
        fprintf(stderr,
                "{\"allocs\":%" PRIu64 ",\"frees\":%" PRIu64 ",\"dups\":%" PRIu64 ",\"drops\":%" PRIu64
                ",\"reuse_excl\":%" PRIu64 ",\"reuse_test_pass\":%" PRIu64 ",\"reuse_test_fail\":%" PRIu64
                ",\"wops\":%" PRIu64 ",\"frame_allocs\":%" PRIu64 ",\"frame_frees\":%" PRIu64
                ",\"frame_bytes\":%" PRIu64 ",\"peak_rss_kb\":%ld,\"wall_ns\":%" PRId64 "}\n",
                n_alloc, n_free, n_dup, n_drop, n_reuse_excl, n_test_pass, n_test_fail, n_wop, n_frame_alloc,
                n_frame_free, n_frame_bytes, ru.ru_maxrss, wall_ns);
#else
        fprintf(stderr,
                "fixed: this binary was built without counters (`fixed ship --stats` builds them in); time and memory only\n"
                "{\"counters\":false,\"peak_rss_kb\":%ld,\"wall_ns\":%" PRId64 "}\n",
                ru.ru_maxrss, wall_ns);
#endif
    }
    return 0;
}
