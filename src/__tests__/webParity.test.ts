import * as path from 'node:path';

import { beforeAll, describe, expect, it } from '@jest/globals';
import ts from 'typescript';

import * as nativeEntry from '../index';
import * as webEntry from '../index.web';

const REPO_ROOT = path.resolve(__dirname, '../..');
const NATIVE_ENTRY = path.join(REPO_ROOT, 'src/index.tsx');
const WEB_ENTRY = path.join(REPO_ROOT, 'src/index.web.tsx');

/** Building a program reads every declaration both entries depend on. */
const PROGRAM_TIMEOUT_MS = 120_000;

interface ExportShape {
  /** `value` exports exist at runtime; `type` exports are erased. */
  kind: 'value' | 'type';
  /** Normalized type text, so parameter names do not count as drift. */
  shape: string;
}

function hasFlag(symbol: ts.Symbol, flag: ts.SymbolFlags): boolean {
  // eslint-disable-next-line no-bitwise -- the compiler API exposes bit flags
  return (symbol.flags & flag) !== 0;
}

function loadCompilerOptions(): ts.CompilerOptions {
  const parsed = ts.getParsedCommandLineOfConfigFile(
    path.join(REPO_ROOT, 'tsconfig.json'),
    {},
    {
      ...ts.sys,
      onUnRecoverableConfigFileDiagnostic: (diagnostic) => {
        throw new Error(
          ts.flattenDiagnosticMessageText(diagnostic.messageText, '\n')
        );
      },
    }
  );
  if (parsed === undefined) {
    throw new Error('tsconfig.json could not be read');
  }
  return { ...parsed.options, incremental: false, noEmit: true };
}

/**
 * The web entry prefixes unused parameters with `_`, which is a naming
 * convention rather than a signature difference.
 */
function withoutUnusedPrefix(text: string): string {
  return text.replace(/([(,]\s*)_(\w)/g, '$1$2');
}

/** Spell out the members so two separate declarations compare by structure. */
function describeMembers(
  checker: ts.TypeChecker,
  type: ts.Type,
  location: ts.Node
): string {
  return checker
    .getPropertiesOfType(type)
    .map((member) => {
      const optional = hasFlag(member, ts.SymbolFlags.Optional) ? '?' : '';
      const memberType = checker.getTypeOfSymbolAtLocation(member, location);
      return `${member.name}${optional}: ${checker.typeToString(memberType)}`;
    })
    .sort()
    .join('; ');
}

function exportsOf(
  program: ts.Program,
  checker: ts.TypeChecker,
  fileName: string
): Map<string, ExportShape> {
  const sourceFile = program.getSourceFile(fileName);
  if (sourceFile === undefined) {
    throw new Error(`${fileName} is not part of the program`);
  }
  const moduleSymbol = checker.getSymbolAtLocation(sourceFile);
  if (moduleSymbol === undefined) {
    throw new Error(`${fileName} is not a module`);
  }

  const shapes = new Map<string, ExportShape>();
  for (const exported of checker.getExportsOfModule(moduleSymbol)) {
    const symbol = hasFlag(exported, ts.SymbolFlags.Alias)
      ? checker.getAliasedSymbol(exported)
      : exported;
    if (hasFlag(symbol, ts.SymbolFlags.Value)) {
      const type = checker.getTypeOfSymbolAtLocation(symbol, sourceFile);
      shapes.set(exported.name, {
        kind: 'value',
        shape: withoutUnusedPrefix(
          checker.typeToString(type, undefined, ts.TypeFormatFlags.NoTruncation)
        ),
      });
    } else {
      shapes.set(exported.name, {
        kind: 'type',
        shape: describeMembers(
          checker,
          checker.getDeclaredTypeOfSymbol(symbol),
          sourceFile
        ),
      });
    }
  }
  return shapes;
}

describe('web entry parity', () => {
  let nativeShapes: Map<string, ExportShape>;
  let webShapes: Map<string, ExportShape>;

  beforeAll(() => {
    const program = ts.createProgram({
      rootNames: [NATIVE_ENTRY, WEB_ENTRY],
      options: loadCompilerOptions(),
    });
    const checker = program.getTypeChecker();
    nativeShapes = exportsOf(program, checker, NATIVE_ENTRY);
    webShapes = exportsOf(program, checker, WEB_ENTRY);
  }, PROGRAM_TIMEOUT_MS);

  it('finds the public surface at all', () => {
    expect(nativeShapes.get('enqueue')?.kind).toBe('value');
    expect(nativeShapes.get('SyncItem')?.kind).toBe('type');
    expect(nativeShapes.get('SyncProviderProps')?.kind).toBe('type');
  });

  it('exports the same names, values and types alike, from both entries', () => {
    const nativeNames = [...nativeShapes.keys()];
    const webNames = [...webShapes.keys()];

    expect({
      missingOnWeb: nativeNames.filter((name) => !webShapes.has(name)),
      missingOnNative: webNames.filter((name) => !nativeShapes.has(name)),
    }).toEqual({ missingOnWeb: [], missingOnNative: [] });
  });

  it('gives every export the same kind and shape on both entries', () => {
    const drifted = [...nativeShapes]
      .filter(([name, shape]) => {
        const web = webShapes.get(name);
        return (
          web !== undefined &&
          (web.kind !== shape.kind || web.shape !== shape.shape)
        );
      })
      .map(([name, shape]) => ({
        name,
        native: shape,
        web: webShapes.get(name),
      }));

    expect(drifted).toEqual([]);
  });

  it('agrees with what the entries actually export at runtime', () => {
    const runtimeKeys = (entry: object) =>
      Object.keys(entry)
        .filter((key) => !key.startsWith('__'))
        .sort();

    expect(runtimeKeys(webEntry)).toEqual(runtimeKeys(nativeEntry));
    expect(runtimeKeys(nativeEntry)).toEqual(
      [...nativeShapes]
        .filter(([, shape]) => shape.kind === 'value')
        .map(([name]) => name)
        .sort()
    );
  });
});
