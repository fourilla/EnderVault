/**
 * Reviewed directory transfer plans shared by copy, move, and pending-upload merges.
 * Planning and approval precede per-item publication; finalization handles source
 * cleanup and ownership completion. This is not an atomic whole-tree transaction.
 *
 * <p>DirectoryTransferService orchestrates vault copy/move; the pending execution
 * service additionally protects staging ownership. FileCommitCoordinator owns the
 * underlying publication journal. TaskManagerService owns scheduling, not recovery.
 *
 * <p>The directory-merges storage/API paths and DIRECTORY_MERGE wire identifiers
 * remain stable contracts. They do not restrict this package to conflicting trees.
 */
package io.github.fourilla.endervault.directorytransfer;
