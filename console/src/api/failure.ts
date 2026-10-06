/** An answer of the Engine that is not a success, or no usable answer at all (`status` 0). */
export class ApiFailure extends Error {
  constructor(
    readonly status: number,
    /** The JSON body of the answer, if it had one. */
    readonly body: unknown = null,
    message = `the Engine answered ${status}`,
  ) {
    super(message);
    this.name = 'ApiFailure';
  }
}
