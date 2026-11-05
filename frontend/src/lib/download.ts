/**
 * Streams a file download to disk. With the File System Access API (Chromium) the response body
 * is piped straight to the chosen file, so memory use is constant regardless of file size.
 * Elsewhere it falls back to a Blob, which buffers the whole file in memory.
 */
export async function downloadFile(fileId: number, fileName: string): Promise<void> {
  const token = localStorage.getItem('token');
  const res = await fetch(`/api/files/${fileId}/download`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
  if (!res.ok || !res.body) throw new Error(`Download failed (${res.status})`);

  const picker = (window as unknown as {
    showSaveFilePicker?: (o: { suggestedName: string }) => Promise<{ createWritable(): Promise<WritableStream<Uint8Array>> }>;
  }).showSaveFilePicker;

  if (picker) {
    const handle = await picker({ suggestedName: fileName });
    await res.body.pipeTo(await handle.createWritable());
    return;
  }
  const url = URL.createObjectURL(await res.blob());
  const a = document.createElement('a');
  a.href = url;
  a.download = fileName;
  a.click();
  URL.revokeObjectURL(url);
}
