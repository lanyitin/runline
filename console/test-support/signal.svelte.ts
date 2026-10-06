// A value that Svelte can watch, for the tests of what reacts to a change of state.
export class Signal<T> {
  value = $state<T>() as T;
  constructor(initial: T) {
    this.value = initial;
  }
}
