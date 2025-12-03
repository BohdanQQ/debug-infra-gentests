# Expanding `hooklib`

## Multithreading support

### Status quo - quick notes

* **no thread identification**
* `__attribute__((constructor))` and `__attribute__((destructor))` as the core architectural points need to be investigated - are they guaranteed to be called exactly once at the designated points in time?


### Thread safety - WRITER side

* protocol guards "buffers" - atomically counting them - in a single-consumer-single-producer pattern
* free, full semaphores - as long as the readers and writers are greedy, the writers should always "eventually" have buffers to write to
  * assumes the used **semaphores are fair** (T1 loops forever, spams buffers, T2 doesn't get to write and is held back by the priority of T1)
* while buffers are "synchronized", the bump pointer (current allocation scheme within one buffer) **is not!**
  * 2 threads writing N, M bytes into the same buffer with capacity > N + M => race condition on the bump pointer
  * possible solutions: **synchronize** access to the **bumper pointer** or **parallelize** accross **free buffers** (both solutions: while **keeping the reader single-threaded** - for now)

#### Parallelizing free buffers

* some indication that a buffer is being used...
  * seems to still reduce the problem to some synchronization around "the current" buffer? (or auxiliary count + CAS)
* thinking of how the buffers will look like: more than one non-full buffer - how does that play with the protocol?
  * stop condition = "10" empty buffers -> nonempty buffer -> reset (not an error condition!)
  * should be okay?

## Types

Current pain points (sorted by descending **priority**):
* compound (container) types are less-than-optimally supported
* complex extension process - serialization and deserialization done in one function (why?!)
* complex runtime process - ad-hoc protocol is a hefty tech. debt
* relatively complex process on the high level - involves modification of a large number of files

## Questions:

**Must keep thinking about: multithreaded support**

1. **How** to implement **compound types** more elegantly?
2. **Where** are the **variation points** of the whole `hooklib`-instrumentation interaction?
3. **Are there alternative** approaches to the value (de)serialization problem to which we could delegate the burden?
  * I don't think there will be enough time to think more deeply about the protocol itself

