/* Fixed runtime for the Phase 4.0 backend slice (docs/plans/phase-4.0-backend-slice.md §4.7).
 *
 * Linked by `clang` with the LLVM IR the compiler emits; the compiler itself emits only LLVM IR.
 * Provides allocation, reference counting, strings, the console, scalar formatting and the
 * counters behind the baseline. Calling convention: every function consumes the boxed values
 * passed to it.
 *
 * Heap object layout (spec/memory_management.md Rule PR3.1): a 16-byte header, then the payload.
 * `nptr` boxed fields come first in the payload, so one generic routine can drop any object.
 * A negative count marks a static object (string constants), which is never counted or freed.
 */
#define _POSIX_C_SOURCE 200809L
#include <inttypes.h>
#include <math.h>
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
    uint32_t nptr;
} fx_header;

typedef struct fx_string {
    fx_header h;
    int64_t len;
    char bytes[];
} fx_string;

static uint64_t n_alloc, n_free, n_dup, n_drop, n_reuse;
static struct timespec t_start;

static void fx_die(const char *msg, int code) {
    fflush(stdout);
    fprintf(stderr, "fixed: %s\n", msg);
    exit(code);
}

void *fx_alloc(uint32_t tag, uint32_t nptr, size_t payload) {
    fx_header *h = malloc(sizeof(fx_header) + payload);
    if (!h) fx_die("out of memory", 102);
    h->rc = 1;
    h->tag = tag;
    h->nptr = nptr;
    n_alloc++;
    return h;
}

void fx_drop(fx_header *h);

/* Reuse (spec/memory_management.md §11), drop guided: the token is taken where the cell would
   have been dropped. The cell's children are dropped (lowering duplicated the ones it read, so
   those return to the count they had), and the empty cell (nptr 0) is the token: a constructor
   fills it, or, if none does, it is dropped like any value. */
static fx_header *reclaim(fx_header *h) {
    fx_header **fields = (fx_header **)(h + 1);
    for (uint32_t i = 0; i < h->nptr; i++) fx_drop(fields[i]);
    h->nptr = 0;
    n_reuse++;
    return h;
}

/* Exclusively owned: no test. FX_CHECK_EXCL (test builds) verifies the claim. */
void *fx_reuse_excl(fx_header *h) {
#ifdef FX_CHECK_EXCL
    if (h->rc != 1) fx_die("internal error: reused a cell that is not exclusively owned", 103);
#endif
    return reclaim(h);
}

/* Shared: Koka's path. Reuse when the count says no one else holds it; else release our share. */
void *fx_reuse(fx_header *h) {
    if (h->rc == 1) return reclaim(h);
    fx_drop(h);
    return NULL;
}

/* A cell for a constructor: the reuse token if there is one, else a fresh allocation. */
void *fx_alloc_at(void *token, uint32_t tag, uint32_t nptr, size_t payload) {
    if (!token) return fx_alloc(tag, nptr, payload);
    fx_header *h = token;
    h->rc = 1;
    h->tag = tag;
    h->nptr = nptr;
    return h;
}

void fx_dup(fx_header *h) {
    if (!h) return; /* the zero result of an aborted call */
    n_dup++;
    if (h->rc > 0) h->rc++;
}

/* ponytail: recursive; a long list exhausts the native stack. Phase 4 drops by tail call (Rule PR7.3). */
void fx_drop(fx_header *h) {
    if (!h) return;
    n_drop++;
    if (h->rc < 0) return;
    if (--h->rc == 0) {
        /* A closure (tag 0xFFFFFFFF) keeps two code pointers in slots 0 and 1 (exclusive and
           shared entry); its boxed captures follow. */
        fx_header **fields = (fx_header **)(h + 1) + 2 * (h->tag == UINT32_MAX);
        for (uint32_t i = 0; i < h->nptr; i++) fx_drop(fields[i]);
        free(h);
        n_free++;
    }
}

void fx_panic_div(void) { fx_die("panic: integer division by zero", 101); }

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

/* ---- parsing (builtin.parse_u64) ---- */

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
    fx_u_main();
    fflush(stdout);
    if (getenv("FIXED_RT_STATS")) {
        struct timespec t_end;
        struct rusage ru;
        clock_gettime(CLOCK_MONOTONIC, &t_end);
        getrusage(RUSAGE_SELF, &ru);
        int64_t wall_ns = (t_end.tv_sec - t_start.tv_sec) * 1000000000LL + (t_end.tv_nsec - t_start.tv_nsec);
        fprintf(stderr,
                "{\"allocs\":%" PRIu64 ",\"frees\":%" PRIu64 ",\"dups\":%" PRIu64 ",\"drops\":%" PRIu64
                ",\"reuses\":%" PRIu64 ",\"peak_rss_kb\":%ld,\"wall_ns\":%" PRId64 "}\n",
                n_alloc, n_free, n_dup, n_drop, n_reuse, ru.ru_maxrss, wall_ns);
    }
    return 0;
}
