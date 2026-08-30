import { useEffect } from 'react';
import type { useUploadManager } from '../app/uploads/UploadManagerContext';

type UploadManager = ReturnType<typeof useUploadManager>;

export function useAdminUploadDropzone(manager: UploadManager, destinationPath: string) {
  useEffect(() => {
    const dropZone = document.querySelector<HTMLElement>('[data-file-dropzone]');
    const overlay = document.getElementById('dropUploadOverlay');
    if (!dropZone) return undefined;

    let dragDepth = 0;
    let internalDrag = false;
    const itemSelector = '.browser-card, .table-wrap a, .thumb-media, .video-thumb-wrap, .thumb-placeholder, .thumb-extension';
    const isFileTransfer = (transfer: DataTransfer | null) => !internalDrag
      && Boolean(transfer)
      && [...(transfer?.types ?? [])].includes('Files');
    const showOverlay = () => {
      dropZone.classList.add('drag-upload-active');
      overlay?.setAttribute('aria-hidden', 'false');
    };
    const hideOverlay = () => {
      dragDepth = 0;
      dropZone.classList.remove('drag-upload-active');
      overlay?.setAttribute('aria-hidden', 'true');
    };
    const reset = () => {
      internalDrag = false;
      hideOverlay();
    };
    const filesFrom = (transfer: DataTransfer) => {
      const items = [...(transfer.items ?? [])];
      let hasDirectory = false;
      if (items.length === 0) return { files: [...transfer.files], hasDirectory };
      const files = items.flatMap((item) => {
        if (item.kind !== 'file') return [];
        const entry = typeof item.webkitGetAsEntry === 'function' ? item.webkitGetAsEntry() : null;
        if (entry?.isDirectory) {
          hasDirectory = true;
          return [];
        }
        const file = item.getAsFile();
        return file ? [file] : [];
      });
      return { files, hasDirectory };
    };

    const onDragStart = (event: DragEvent) => {
      internalDrag = true;
      hideOverlay();
      if (event.target instanceof Element && event.target.closest(itemSelector)) {
        event.preventDefault();
        window.setTimeout(reset, 0);
      }
    };
    const onDragEnter = (event: DragEvent) => {
      if (!isFileTransfer(event.dataTransfer)) return;
      event.preventDefault();
      dragDepth += 1;
      showOverlay();
    };
    const onDragOver = (event: DragEvent) => {
      if (!isFileTransfer(event.dataTransfer)) return;
      event.preventDefault();
      if (event.dataTransfer) event.dataTransfer.dropEffect = 'copy';
      showOverlay();
    };
    const onDragLeave = (event: DragEvent) => {
      if (!isFileTransfer(event.dataTransfer)) return;
      dragDepth -= 1;
      if (dragDepth <= 0) hideOverlay();
    };
    const onDrop = (event: DragEvent) => {
      if (!isFileTransfer(event.dataTransfer) || !event.dataTransfer) return;
      event.preventDefault();
      event.stopPropagation();
      hideOverlay();
      const { files, hasDirectory } = filesFrom(event.dataTransfer);
      if (hasDirectory) {
        window.EnderVault?.showToast('error', files.length
          ? 'Directory items were skipped.'
          : 'Directory uploads are not supported yet.');
      }
      if (files.length > 0) manager.startFiles(files, destinationPath);
    };
    const preventOutsideDrop = (event: DragEvent) => {
      if (!isFileTransfer(event.dataTransfer)) return;
      if (event.type === 'drop' && dropZone.contains(event.target as Node)) return;
      event.preventDefault();
      if (event.type === 'drop') hideOverlay();
    };

    dropZone.addEventListener('dragstart', onDragStart);
    dropZone.addEventListener('dragenter', onDragEnter);
    dropZone.addEventListener('dragover', onDragOver);
    dropZone.addEventListener('dragleave', onDragLeave);
    dropZone.addEventListener('drop', onDrop);
    document.addEventListener('dragover', preventOutsideDrop);
    document.addEventListener('drop', preventOutsideDrop);
    document.addEventListener('dragend', reset);
    window.addEventListener('blur', reset);
    return () => {
      reset();
      dropZone.removeEventListener('dragstart', onDragStart);
      dropZone.removeEventListener('dragenter', onDragEnter);
      dropZone.removeEventListener('dragover', onDragOver);
      dropZone.removeEventListener('dragleave', onDragLeave);
      dropZone.removeEventListener('drop', onDrop);
      document.removeEventListener('dragover', preventOutsideDrop);
      document.removeEventListener('drop', preventOutsideDrop);
      document.removeEventListener('dragend', reset);
      window.removeEventListener('blur', reset);
    };
  }, [destinationPath, manager]);
}
