# Effects and handlers

I/O, logging, failure, concurrency — all algebraic effects: declared,
tracked in the type, and handled with composable `handle` blocks
([`spec/effects.md`](../effects.md)). No monad transformers, no
colored functions.

## Rows compose with `+`

A function's `with` clause is its whole effect story — unordered, and
in the type, so "what does this function do?" is a type question:

```
fn read_config(path: String) -> Config
    with FileSystem + Fail of String =
    let contents = FileSystem.read_file(path).fold(
        (v) -> v,
        (e) -> Fail.fail(e),
    )
    parse_config(contents)
```

Pure helpers just have no `with` clause at all.

## Nested handlers

Each `handle` removes one effect from the row — stack them to run a
program under a full environment. Handlers are ordinary code: the
`Console` handler below is the whole "runtime", no magic:

```
handle (do:
    handle (do:
        handle (do:
            let config = read_config("server.conf")
            run_server(config)
        ):
            Fail.fail(e) =>
                builtin.stderr_write("Fatal: ".concat(e))
                builtin.exit(1)
    ):
        Log.log(level, msg) =>
            builtin.stderr_write("[".concat(level).concat("] ").concat(msg))
            resume(())
):
    Console.print_line(msg) =>
        builtin.stdout_write(msg.concat("\n"))
        resume(())
    Console.read_line() =>
        resume(builtin.stdin_read_line())
```

`resume(v)` continues the captured continuation; an abortive operation
(one returning `!`) never resumes, so its clause result *is* the
handle block's result. Swap a handler, keep the program: the same
`read_config` runs against a real filesystem, an in-memory one, or a
test double that fails on demand.

## Concurrency as effects

There is no built-in scheduler or async/await — spawning and yielding
are effects like any other, and *you* can implement the scheduler:

```
effect Channel of A:
    fn send(value: A) -> ()
    fn receive -> A

effect Async:
    fn spawn(task: () -> () with Async + E) -> () with E
    fn yield_now -> ()
```

A cooperative scheduler is a `handle` over `Async` whose queue is just
a capability; a channel is a `handle` over `send`/`receive` threading a
buffer. The task code in between is ordinary, effect-typed
programming — see `run_scheduler` and `run_with_channel` in the source
example for complete implementations.

Because the loops driving a scheduler are unbounded, they are bounded
by a `max_steps` fold — the totality workaround from
[Recursion schemes](recursion-schemes.md#when-you-need-a-loop) — until
the codata story lands.

> Source: [`examples/08_effects_handlers.fixed`](https://github.com/Constructive-Programming/fixed/blob/main/examples/08_effects_handlers.fixed)
