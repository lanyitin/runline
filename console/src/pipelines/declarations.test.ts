import { describe, expect, test } from 'vitest';
import { declarationsOf } from './declarations';

const warning = (kind: string, resource: string) => ({ kind, resource, message: 'the Engine says' });

describe('the shared resources a pipeline declares', () => {
  test('each with the type it expects, or none, in the order declared, and available when nothing is said against it', () => {
    expect(
      declarationsOf({ resources: ['printer', 'report'], resourceTypes: { report: 'file' } }, []),
    ).toEqual([
      { name: 'printer', declaredType: null, status: 'available' },
      { name: 'report', declaredType: 'file', status: 'available' },
    ]);
  });

  test('takes its status from the warnings of the Engine: not defined, disabled, of another type, a type out of the set', () => {
    expect(
      declarationsOf(
        { resources: ['a', 'b', 'c', 'd'], resourceTypes: { c: 'file', d: 'quantum' } },
        [
          warning('resource_unknown', 'a'),
          warning('resource_disabled', 'b'),
          warning('resource_type_mismatch', 'c'),
          warning('resource_type_unknown', 'd'),
          warning('network_host_has_resource', 'a'),
        ],
      ).map((d) => [d.name, d.status]),
    ).toEqual([
      ['a', 'unknown'],
      ['b', 'disabled'],
      ['c', 'type_mismatch'],
      ['d', 'type_unknown'],
    ]);
  });

  test('says first that a resource is not defined, before what is wrong with the type it expects', () => {
    expect(
      declarationsOf({ resources: ['d'], resourceTypes: { d: 'quantum' } }, [
        warning('resource_type_unknown', 'd'),
        warning('resource_unknown', 'd'),
      ])[0].status,
    ).toBe('unknown');
  });
});
