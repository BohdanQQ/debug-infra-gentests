# TODO:

**based on** [Future work](#future-work) of the original [research project](#original-research-project-progress-tracker)

* ~~investigate exceptions - try to implement~~
    * ~~look at the noted implementation ~~
    * ~~add e2e-test that fails~~
    * ~~quick prototype in plugin~~
* ~~investigate `protobuf` as a serialization tool~~
    * ~~payloads become [len + protobuff-part], types encoded in the protobuf schema?~~
    * ~~performance compared to current approach? - memory, cpu~~
        * ~~how large of a tradeoff is this against dependency delegation and overall better maintainability bring?~~ (medium, but worth it)
    * ~~usability?~~
    * ~~usable from the Rust `llcap-server`?~~
* ~~decide on the overall approach of tackling multithreading~~
    * experimentation with the role swap is easier to implement
    * ~~runtime snapshots will bring more usability (easier restart, ensured program state reconstruction)~~
* ~~type extension~~
    * ~~re-architecture~~ - at least split the functions
    * investigate constant autogeneration
        * provide data in config regarding custom types instead of requiring recompilation
* addres config mess
    * ~~look at defaults - required/nonrequired existence of directories/files~~
    * ~~ overall architecture - delegation of the option values further down the line~~
    * ~~config architecture - what can be abstracted into configs~~

# TOPIC: Multithreading support

* ~~selected approach: inversion of fork mechanism~~
    * ~~split LLCAP functionality (switch)~~
    * ~~split hooklib functionality (add metadata to select fork mode at runtime)~~
* thread-ID mapping 
    * in `llcap-server`
    * in `hooklib`
* thread-safety of `hooklib` in `mt` mode
* try each combination (thread ID needed) of args/thread
    * ~~craft e2e test code~~
    * craft e2e test cases
* tackle killing of a detached child process
    * if parent dies and child remains, IMO llcap-server cannot reach the child process
        * this is not handled explicitly by `llcap-server`, but by the `hooklib` (child detects parent's timeout -> exits)
    * ~~also tackle the **wrong** `exit` calls~~
        * ~~exit in a child can cause parent to hang, etc.~~
        * ~~in child, always use `std:::quick_exit`~~


# TOPIC: Type extensions
* ~~protobuf~~
* split functionality, make extensions more bearable
* document - update [docs](./development-manual.md)

# TOPIC: LLVM metadata patch removal?
* TODO

# TOPIC: Configuration (low priority)

* common TOML structure
* TOML parser in hooklib, clang/llvm plugins, llcap-server
* if done, document


Original Research Project progress tracker:
---

* ~~prepare examples for function tracing prototype~~
* ~~prepare examples for parameter capture prototype~~
* ~~think about non-deterministic traces~~
    * ~~do we permit relying on function call determinism?~~
* ~~think about C++ objects - capturing inside of them, this, ...~~
* ~~better source organisation, READMEs where possible~~
    * ~~a link tree in the root README~~
* ~~move `llvm-project` somewhere more sensible~~
* ~~unify SOLVED vs DONE items~~
* ~~public repo?~~
* ~~remove ZMQ references entirely (too expensive to maintain)~~
    * ~~ensure ALL links to ZMQ functionality actually link to a revision where ZMQ is available~~
        * ~~ensure the revision is the same, where possible~~
    * also ensure legacy examples work (test-pass-working)
* more diagrams
* ~~split 00-testing-* to analysis and progress updates~~
    * try link each other?
* ~~move `Capturing function arguments` somewhere else... and document the status quo~~
* establish terminology used *eveywhere* (but initial documents?)
    * test driver === `llcap-server`
    * test coordinator === the parent of all forked test cases
    * argument packet of a function `foo` === n-tuple of `foo`s arguments
    * argument capture of a function `foo` === (process of obtaining of the **or** the) set of all recorded argument packets of a function `foo`
    * hooklib, hook library, ...
    * ... ?
* ~~bug in the `hook_arg_preabmle` function~~:
    * ~~Child process is spawned at the first call, not the n-th one as desired by instrumentation ! this was unexpected / unnoticed~~
    * ~~the `register_call` gets called anyway so the behavior looks correct~~
    * ~~the only difference is that the timeout is counted from the first call, not the n-th desired call~~
* ~~make `/tmp/llcap-test-server` a constant~~
* ~~?? add a global test timeout for the case there is an infinite loop and the test is never executed~~
* ~~testing: use `trace.out` (llcap `-e`) for testing selection (even if it means running the tested binary twice) (implemented via name matching)~~
    * update in readme
* ~~stop the test right when return is reached~~ (see [progress/july](./00-progress-updates.md#july))

## Final polishing

* add some tests ~~for `llcap-server` and~~ hooklib now that everything seems to be more or less stable? [in progress - TODO: hooklib tests]
    * ~~first C++-ify the `hook.cpp` file...~~
* ~~proper automatic cleanup / overwrite policies for outputs [in progress - especially in arg capture]~~
* ~~make call-tracing stage require arguments and make it run the binary itself~~
    * ~~adjust readmes of examples (2)~~
* ~~make call-tracing and arg-capture stages launch the ipc-finalizer after the program crashes~~
    * ~~update readmes of examples (2)~~
* try out various scenarios (timeouts of test cases, crashes)
* ~~add a simple argument replacement example~~
    * ~~add a replicated C example (also serves as verification that we can also link/build with C programs)~~
* add a guide for adding custom type support
    * make the UX of this in the LLVM plugin better  
* ~~try compilation from scratch in a new environment~~ 
* ~~(optionally?) terminate program right after tested function returns~~
    * exception handling???
        * document, document turning on, ...
* investigate debug metadata usage instead of current approach
* validate argument splitting works/is disallowed
* complete `llcap-server` readme
    * ~~structure~~
    * ~~build reqs (bindgen, header file)~~
    * ~~update cmdline options~~
    * ~~link examples~~
* ~~document debugging/development techniques~~
    * ~~verbosity of llcap server~~
    * ~~passing arguments to the llvm plugins~~


**List of topics:**

* [Data Capture Library](#topic-data-capture-library)
* [C++ support](#topic-c-support)
* [LLVM IR approach](#topic-llvm-ir-approach)
* [Typed value capture](#topic-typed-value-capture)
* [Testing / Isolation](#topic-testing--isolation)
* [Papers](#topic-papers)
* [Modification of incoming arguments](#topic-modification-of-incoming-arguments)
* [Extras](#topic-extras)

# TOPIC: Data Capture Library

* thoroughly check for alignment issues and other UB
    * check for alignment on llcap-server side [in progress]

## Capturing funciton arguments

* **[DONE]** how to treat **templated** code
    * let `X` be the set of types allowed for recording:
    * `template <class T>`, parameter of type `T`, where all instantiations of `T` in `X` - should be OK with naive source modification
    * not all instantiations have `T` in `X` -> compilation errors
        * how to resolve?
        * can clang provide per-instantiation decisionmaking? how will that affect compile times/feasability?

* **[DONE]** how to treat type aliases?
    * should be doable via clang's APIs (the underlying type should be resolved, right?)

## Capturing execution trace 

* use other tools?

* **[DONE]** C vs C++ function scope tracking
    * for now I only focus on C++ code, this method should allow tracing of even code which throws exceptions
    * C has no "reasonably non-colliding" namespaces
    * on the other hand: no annoying exceptions

* **[DONE]** use of C++'s `auto` keyword
    * limits C heavily (`auto` not used widely)
    * intricacies of C++'s type system & reference semantics
        * ensuring that modified source code makes exactly the same side effects as the unmodified one

# TOPIC: C++ support

*  **[DUPLICATE]** ~~methods of objects with AST modification (abandoned?)~~

# TOPIC: LLVM IR approach

* shows more promise than AST modification
* trouble with filtering (non) library funcitons - information not available in the IR
    * **[DONE]** **Idea**: add metadata to the functions in the IR that could tell the LLVM pass if the function is `#include`d, library, ...
        * [LLVM Discussion](https://discourse.llvm.org/t/how-to-distinguish-between-user-defined-function-in-a-program-and-library-functions/54401/7)
    * more metadata could help with a GUI integration later (emitting line information metadata - *using debug information metadata*, ...)
    * **[DONE]** inject custom metadata regarding (non)library/builtin functions
* **[DONE]** capturing inside a lambda, overall lambda instrumentation
* **[DONE]** LLVM [intrinsics](http://llvm.org/docs/LangRef.html#intrinsics) - can they be used?
    * **warning, can affect code generation**
    * lack of documentation (e.g. `llvm.memcpy`)
    * even then, seem inconsequential

* **[DONE]** **important for custom IR metadata**: `isExpansionInMainFile` vs `isExpansionInSystemHeader`
    * Does `isExpansionInMainFile` exclude functions in **any** header files?
        * If yes, does `isExpansionInSystemHeader` solve this?

* **[DONE]** is the custom metadata approach outlined in [](./01-llvm-ir-metadata-emission.md#custom-llvm-ir-metadata) correct?
* **[DONE]** is it possible to inject metadata in the AST Matching phase?
    * e.g. visit every `FunctionDecl` and add data (key-value string pairs) that would later be injected as LLVM IR metadata? (this is a question because the AST itself (and `FunctionDecl`) seemed to be "`const`ant" throughout my experimentation)

* **[DONE]** investigate [Clang IR (CIR)](https://llvm.github.io/clangir/)
    * relatively new feature (doesn't seem to be used with my build of LLVM) - *ClangIR upstreaming RFC was accepted in Feb 2024,*
    * conclusion: probably useful in far future (dialect augmentation)

* **[DONE]** add an option to metadata export plugin to use mangled name approach via a plugin option
    * supply LLVM pass & use `-mllvm -llcap-filter-by-mangled` when compiling

* `custom-metadata-pass` - document build steps

# TOPIC: Typed value capture
* **[DONE]** document hurdles / bypasses / alternatives to LLVM metadata approach
    * `this` pointer
    * ABIs that return structures by value in registers (`sret`, `structureReturn`, `StructRet`)
    * **?maybe?** - dive deeper into the ABI specs and cause runtime error within the LLVM plugin if the target ABI is unknown/unhandled
* **[DONE]** document current approach
* **[DONE]** discuss possible breakage points
    * structures fitting 64 bits passed in registers
    * changes in argument order
* try to use LLVM debug information metadata to perform instrumentation **without** the LLVM metadata customization  

# TOPIC: Testing / Isolation
* isolation inspiration - to investigate? - (`clone`/`fork` used): [mem-isolate](https://github.com/brannondorsey/mem-isolate)
* document architecture of testing, new hooklib, diagrams, ...
* send termination signal to a fork that timed out
* `I | [main] Run program for fn m: F7DB0979 f: 03000000` - add name and module path (nonverbose output)

# TOPIC: Papers

* investigate memory instruction instrumentation via Pin from the AURORA tool
    * [repo](https://github.com/RUB-SysSec/aurora)

# TOPIC: Modification of incoming arguments
* **[DONE?]** are there assumptions in the IR, that would break if we replaced arguments mid-function?
    * **[DONE]** the idea is to "introduce new `alloca`d variable (`%x`)" for each `%n` argument and replace each `%n` occurence with the new `%x`
* `const`ness vs `IR`-level modifications
    * does `-O3`, etc. impact the number of arguments in IR? (e.g. via more agressive const propagation?)
    * if prohibitive for argument "hijacking", can we at least "remove" `const`
* **[DONE]** validate approach that modifies the IR
* add smaller demo (replace the current demo) that shows everything on a smaller scale
    * particularly the argument replacement part (simple visual confirmation for the user)
* document the architecture, add diagrams

# TOPIC: Extras
* **[CLOSED]** report mangling bug (after rebuild of latest llvm project)
    * actually a [larger issue](https://discourse.llvm.org/t/rfc-clang-diagnostic-for-demangling-failures/82835) ([pull request for a diagnostic](https://github.com/llvm/llvm-project/pull/111391))

* so far only on x64 linux ABI - try more?
* attachment to external tools
    * Valgrind, GDB - obvious candidates (instrument to conditionally trigger a breakpoint?)
        * custom types / implemented std::string currently leaks memory (oneshot), questionable yields
    * property-checking tools (unsinged over/underflow, no-progress loops, suspicious iteration counts, recording & analysis of comparisons at particular location)
    * GCOV - coverage
* aggregate mode of the tools - run test cases with multiple failing instances to allow cross-checking (by the user)
* custom "log expressions" that would report into the framework? (additional info) 
    * ! compatibility with LLVM IR
```c++
    RET_T foo(PT p1, PT p2) { 

        /* start of static instrumentation */
        
        /* user-provided expression that can be serialized/logged 
           e.g. p1 * p2 / (p1 - p2) / ... */
        /* end of static instrumentation */
        
        // function body...
    }
```


# **[DONE]** TOPIC: Revamp of buffer reading and writing

* implemented via `BorrowedReadBuffer` and `BorrowedOneshotWritePtr`

Currently the language rules around mutability and sharing when reading and writing shared
memory buffers are enforced by `RefCell` and a check that "no raw pointer is ever casted from
`*const` to `*mut`".

# Future Work

## (DONE as part of Master Thesis) Ensuring all exceptions are always detected

In the `fn-exit` instrumentation mode of the [LLVM pass](../sandbox/01-llvm-ir/llvm-pass/) (used by passing `-mllvm -llcap-instrument-fn-exit` to `clang`), only `ret` and `resume` IR instructions are
instrumented. The instrumentation simply inserts a `call hook_test_epilogue` or `hook_test_epilogue_exc` before `ret` and `resume` respectively.

Other exception-handling instructions are not handled yet. The possible soltions and an
LLVM example is linked [here](./00-progress-updates.md#problems--observations) and [here](./00-progress-updates.md#alternative).

If this feature will be implemented, the `-mllvm -llcap-instrument-fn-exit` shall be the default
and instrumentation without `fn-exit` will only serve for debug purposes perhaps.

## Eliminating the need for the LLVM patch

Currently, the LLVM patch is used to "send" data from the AST plugin to the LLVM pass plugin.
This is because of two reasons:
1. Mark functions that are eligible for call tracing (non-library, non-external) so that LLVM pass
may detect them
2. Mark offsets of arguments "of interest" (e.g. the `std::string` type which appears as a pointer in the IR)

The LLVM IR debug metadata may provide enough information to eliminate `2`. (i.e. it should be possible to map the IR argument index to the C++ type of the arugment).

We are unsure with regards to `1` though. So far, the only option that avoids the LLVM patch is
checking of mangled function names which is an ugly and unstable solution.

## More comfortable extension of the AST and IR plugins

In its current form, the AST and IR plugins are extensible only by direct, albeit simple,
[modification of their sources](./development-manual.md#argument-capture-and-type-detection-mechanisms). We believe the plugins can be adjusted to support extension via some sort of
configuration files supplied to the plugins as arguments (`-mllvm ...`).

Further, the [(de)serialization code](./development-manual.md#deserialization) facilitating 
argument capture and replacement of function arguments could be designed so that extension with a 
custom type would be a matter of creating and linking an additional library (developed alongside 
`hooklib`, using its headers but not recompiling the `hooklib` directly).

The two improvements above would greatly increase developer experience and encourage experimentation
with the tool set.

## Multithreaded target support

Currently we only support instrumentation of single-threaded programs. The main source of this limitation is the [`hooklib` implementation](../sandbox/02-ipc/ipc-hooklib/) which does not synchronize access to or distribute its mutable state among the running threads.
 

 ## Explore fuzzing approaches for injected argument values

 The current form of the workflow simply copies the recorded arguments and "replays" the function calls. We can manually edit the recorded traces to inject other argument values. Creating methods of interactive or automatic addition of argument values would extended the usage of the entire project. Particularly, this setup would allow instrumentation of functions that are "harder to reach" using conventional testing methods in terms of architectural accesibility or the setup of the surrounding program state.

 ## Configurability and consistency

The tool suffers from inconsistent behavior concerning, e.g., the existence of input/output folders and files. It is desirable to unify the behavior.

At the same time, some parts of the workflow can be simplified by introducing "configuration" files
passed to the relevant parts of the tool set. 

One example is the differentiation between the instrumentation phases. Currently, the `llvm-pass` exposes options that have to be appended to the `clang(++)` (compiler) flags with the additional `-mllvm` flag. We switch between call tracing and argument capture/testing instrumentation by supplying either `-mllvm -Test` or `-mllvm -Call`. This is tedious as it requires modification of build files **and regeneration of build infrastructure** every time we want to switch.

By supplying a single configuration file to the `llvm-pass` and providing the configuration in the file, we can make the tool considerably easier to use. Theoretically, this addition would also allow 

* easier integration with a GUI frontend
* providing more parameters in the configuration file, customizing the instrumentation
    * other parameters supplied to the pass currently
    * module-based filtering, etc.

## Prove that adding module metadata does not "break" clang

The inserted module metadata shift the order of other metadata. Some clang tests [fail because of this](../README.md#note-on-clang-tests). The tests match the IR literally and do not allow for
more metadata to exist in between.

Figuring out how to make our patch of `clang/lib/CodeGen/CodeGenModule.cpp` insert the metadata
as the *last* entries would make the tests pass without the need for the lengthy hand-wavy explanation.
