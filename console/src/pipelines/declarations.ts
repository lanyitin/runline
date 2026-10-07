// The shared resources a pipeline declares, as its page shows them (ADR-019): the type each is
// expected to be, and whether it can be used now. The Engine says what is wrong with a declaration
// in the warnings of the pipeline, which it works out again each time it is asked (08-api.md); the
// Console takes the status from them, so that a developer, who cannot read the resources, sees it
// too. Nothing said against a declaration means the resource is defined, enabled and of the
// expected type.

import type { Metadata, Warning } from '../api/model';

/** `available`, or what the Engine said against it: `unknown`, `disabled`, `type_mismatch`, `type_unknown`. */
export type DeclarationStatus = 'available' | 'unknown' | 'disabled' | 'type_mismatch' | 'type_unknown';

export interface Declaration {
  name: string;
  /** The type the pipeline expects; null when it declares only the name. */
  declaredType: string | null;
  status: DeclarationStatus;
}

/** The warnings that are about a declaration, in the order they are said when several are. */
const BY_PRIORITY: Array<[string, DeclarationStatus]> = [
  ['resource_unknown', 'unknown'],
  ['resource_disabled', 'disabled'],
  ['resource_type_unknown', 'type_unknown'],
  ['resource_type_mismatch', 'type_mismatch'],
];

export function declarationsOf(
  metadata: Pick<Metadata, 'resources' | 'resourceTypes'>,
  warnings: Warning[],
): Declaration[] {
  return metadata.resources.map((name) => {
    const said = new Set(warnings.filter((w) => w.resource === name).map((w) => w.kind));
    const found = BY_PRIORITY.find(([kind]) => said.has(kind));
    return {
      name,
      declaredType: metadata.resourceTypes[name] ?? null,
      status: found ? found[1] : 'available',
    };
  });
}
