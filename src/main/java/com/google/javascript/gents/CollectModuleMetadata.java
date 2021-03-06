package com.google.javascript.gents;

import com.google.javascript.jscomp.AbstractCompiler;
import com.google.javascript.jscomp.CompilerPass;
import com.google.javascript.jscomp.JSError;
import com.google.javascript.jscomp.NodeTraversal;
import com.google.javascript.jscomp.NodeUtil;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.Node;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Preprocesses all source and library files to build a mapping between Closure namespaces and
 * file based modules.
 */
public final class CollectModuleMetadata extends AbstractTopLevelCallback implements CompilerPass {

  private final AbstractCompiler compiler;
  private final NameUtil nameUtil;

  final Set<String> filesToConvert;
  final Map<String, FileModule> fileToModule = new LinkedHashMap<>();
  final Map<String, FileModule> namespaceToModule = new LinkedHashMap<>();

  Map<String, FileModule> getFileMap() {
    return fileToModule;
  }

  Map<String, FileModule> getNamespaceMap() {
    return namespaceToModule;
  }

  /** Returns a map from all symbols in the compilation unit to their respective modules */
  Map<String, FileModule> getSymbolMap() {
    Map<String, FileModule> out = new LinkedHashMap<>();
    for (FileModule module : fileToModule.values()) {
      for (String symbol : module.importedNamespacesToSymbols.keySet()) {
        out.put(symbol, module);
      }
    }
    return out;
  }

  public CollectModuleMetadata(AbstractCompiler compiler, NameUtil nameUtil,
      Set<String> filesToConvert) {
    this.compiler = compiler;
    this.nameUtil = nameUtil;
    this.filesToConvert = filesToConvert;
  }

  @Override
  public void process(Node externs, Node root) {
    NodeTraversal.traverseEs6(compiler, root, this);
  }

  @Override
  public void visit(NodeTraversal t, Node n, Node parent) {
    if (!n.isExprResult()) {
      return;
    }

    Node child = n.getFirstChild();
    String filename = n.getSourceFileName();
    switch (child.getToken()) {
      case CALL:
        // Ignore unusual call cases
        // (function() {...})()
        // nested().calls()
        if (child.getFirstChild().getQualifiedName() == null) {
          break;
        }
        switch (child.getFirstChild().getQualifiedName()) {
          case "goog.module":
            if (!parent.getFirstChild().equals(n)) { // is first statement
              compiler.report(JSError.make(n, GentsErrorManager.GENTS_MODULE_PASS_ERROR,
                  "goog.module must be the first top level statement."));
              break;
            }
            registerGoogModule(child, filename, child.getLastChild().getString());
            break;
          case "goog.provide":
            registerProvidesModule(child, filename, child.getLastChild().getString());
            break;
          default:
            break;
        }
        break;
      case GETPROP:
        // Typedefs are often just on property gets, not on assignments.
        JSDocInfo jsdoc = NodeUtil.getBestJSDocInfo(n);
        if (jsdoc != null && jsdoc.containsTypeDefinition() && fileToModule.containsKey(filename)) {
          FileModule module = fileToModule.get(filename);
          module.maybeAddExport(child);
        }
        break;
      case ASSIGN:
        if (!fileToModule.containsKey(filename)) {
          break;
        }
        FileModule module = fileToModule.get(filename);
        module.maybeAddExport(child.getFirstChild());
        break;
      default:
        break;
    }
  }

  /** Registers a goog.module namespace for future lookup. */
  void registerGoogModule(Node n, String file, String namespace) {
    if (fileToModule.containsKey(file)) {
      compiler.report(JSError.make(n, GentsErrorManager.GENTS_MODULE_PASS_ERROR,
          String.format("goog.module files cannot contain other goog.module or goog.provides.")));
      return;
    }
    FileModule module = new FileModule(file, true);
    module.registerNamespaceToGlobalScope(namespace);
  }

  /** Registers a goog.provide namespace for future lookup. */
  void registerProvidesModule(Node n, String file, String namespace) {
    FileModule module;
    if (fileToModule.containsKey(file)) {
      if (fileToModule.get(file).isGoogModule) {
        compiler.report(JSError.make(n, GentsErrorManager.GENTS_MODULE_PASS_ERROR,
            String.format("goog.provide cannot be used in the same file as goog.module.")));
        return;
      }
      module = fileToModule.get(file);
    } else {
      module = new FileModule(file, false);
    }
    module.registerNamespaceToGlobalScope(namespace);
  }

  /** Encapsulates the module provided by each file. */
  class FileModule {
    final String file;

    /** Module is not part of the conversion process and only exists for its exported symbols */
    private final boolean isJsLibrary;
    /** Declared with goog.module rather than goog.provide */
    private final boolean isGoogModule;

    /**
     * Map from each provided namespace to all exported subproperties. Note that only namespaces
     * declared with 'goog.module' or 'goog.provide' are considered provided. Their subproperties
     * are considered exported from the file, but not directly provided. This is to determine what
     * namespaces other files are allowed to reference with 'goog.require'.
     *
     * For example,
     * goog.module('A.B');
     * exports = ...;
     * exports.C = ...;
     * exports.C.D = ...;
     * Would result in providesObjectChildren['A.B'] = {'C'}
     */
    final Map<String, Set<String>> providesObjectChildren = new LinkedHashMap<>();
    /**
     * Map from the fully qualified name being exported to the exported symbol. For example,
     * goog.module('A.B');
     * exports = ...;
     * exports.C = ...;
     * exports.C.D = ...;
     * Would result in:
     * exportedNamespacesToSymbols['exports'] = 'B'
     * exportedNamespacesToSymbols['exports.C'] = 'C'
     */
    final Map<String, String> exportedNamespacesToSymbols = new LinkedHashMap<>();
    /**
     * Map from the fully qualified name that would be imported to the exported symbol. For example,
     * goog.module('A.B');
     * exports = ...;
     * exports.C = ...;
     * exports.C.D = ...;
     * Would result in:
     * importedNamespacesToSymbols['A.B'] = 'B'
     * importedNamespacesToSymbols['A.B.C'] = 'C'
     */
    final Map<String, String> importedNamespacesToSymbols = new LinkedHashMap<>();

    FileModule(String file, boolean isGoogModule) {
      this.file = file;
      this.isGoogModule = isGoogModule;
      this.isJsLibrary = !filesToConvert.contains(file);
    }

    /** Returns if the import statement for this file should use the old 'goog:' namespace syntax */
    boolean shouldUseOldSyntax() {
      return isJsLibrary;
    }

    /** Returns if the file actually exports any symbols. */
    boolean hasExports() {
      return !exportedNamespacesToSymbols.isEmpty();
    }

    /**
     * Register namespace name to global scope so that other files can call 'goog.require'
     * on the qualified name.
     */
    void registerNamespaceToGlobalScope(String namespace) {
      providesObjectChildren.put(namespace, new LinkedHashSet<String>());
      if (isJsLibrary) {
        maybeAddExport(NodeUtil.newQName(compiler, namespace));
      }
      fileToModule.put(file, this);
      namespaceToModule.put(namespace, this);
    }

    /**
     * Attempts to export the name exportsName.
     * Does nothing if exportsName is an invalid export.
     */
    void maybeAddExport(Node exportsName) {
      if (isGoogModule) {
        maybeAddGoogExport(exportsName);
      } else {
        maybeAddProvidesExport(exportsName);
      }
    }

    private void maybeAddGoogExport(Node exportsName) {
      String fullname = providesObjectChildren.keySet().iterator().next();
      if ("exports".equals(exportsName.getQualifiedName())) {
        addExport(exportsName.getQualifiedName(), fullname, nameUtil.lastStepOfName(fullname));
      } else if (exportsName.isGetProp() &&
          "exports".equals(exportsName.getFirstChild().getQualifiedName())) {
        String identifier = exportsName.getLastChild().getString();
        addExport(exportsName.getQualifiedName(), fullname + "." + identifier, identifier);
      }
    }

    private void maybeAddProvidesExport(Node exportsName) {
      String fullname = exportsName.getQualifiedName();
      if (providesObjectChildren.containsKey(fullname) || (exportsName.isGetProp() &&
          providesObjectChildren.containsKey(exportsName.getFirstChild().getQualifiedName()))) {
        addExport(fullname, fullname, nameUtil.lastStepOfName(exportsName));
      }
    }

    private void addExport(String exportName, String importName, String identifier) {
      exportedNamespacesToSymbols.put(exportName, identifier);
      importedNamespacesToSymbols.put(importName, identifier);

      Node namespace = NodeUtil.newQName(compiler, importName);
      if (namespace.isGetProp()) {
        String parentName = namespace.getFirstChild().getQualifiedName();
        if (providesObjectChildren.containsKey(parentName)) {
          providesObjectChildren.get(parentName).add(identifier);
        }
      }
    }
  }
}
