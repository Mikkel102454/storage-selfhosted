// dragndrop-uploader.js
// Expects these globals/functions to exist in your environment:
// - getParameter(key) -> string
// - getFolderLocation(parentId, name) -> Promise<{ uuid: string } | null>
// - uploadFolder(parentId, name) -> Promise<string> (returns new folder uuid)
// - uploadFile(file, folderId, notifyBoolean) -> Promise<void|any>
// - throwInformation(msg), throwSuccess(msg), throwError(msg)
// - reloadStorageLimit(), refreshDirectoryDrive(folderId)

// ------------------------ helpers ------------------------

/**
 * Creates an "ensurePath" function that:
 * - resolves/creates nested folders given a relative path "A/B/C/file.ext"
 * - returns the final parent folder uuid for that file
 */
function makeEnsurer(getFolderLocation, uploadFolder) {
    const cache = new Map(); // key: "parentId/name" -> uuid

    return async function ensurePath(rootFolderId, relPath) {
        const parts = String(relPath).split("/").filter(Boolean);
        const dirs = parts.slice(0, -1); // drop filename
        let parentId = rootFolderId;

        for (const name of dirs) {
            const key = `${parentId}/${name}`;

            if (!cache.has(key)) {
                let uuid = null;
                try {
                    const loc = await getFolderLocation(parentId, name);
                    uuid = (loc && typeof loc.uuid === "string") ? loc.uuid : null;
                } catch (_) {
                    uuid = null;
                }
                if (!uuid) {
                    // create the folder if it doesn't exist
                    uuid = await uploadFolder(parentId, name); // must return uuid string
                }
                cache.set(key, uuid);
            }

            parentId = cache.get(key);
        }

        return parentId;
    };
}

/**
 * Traverse DataTransferItemList (drag-and-drop) via webkit entries.
 * Calls onFile(relativePath, file) for each real file.
 */
async function traverseItems(items, onFile) {
    const entries = Array.from(items || [])
        .filter(i => i && i.kind === "file")
        .map(i => i.webkitGetAsEntry && i.webkitGetAsEntry())
        .filter(Boolean);

    async function walk(entry, path = "") {
        if (entry.isFile) {
            await new Promise((res, rej) =>
                entry.file(async (file) => {
                    try {
                        if (!file || file.size === 0) return res(); // skip zero-byte ghosts
                        await onFile(path + file.name, file);
                        res();
                    } catch (e) { rej(e); }
                }, rej)
            );
        } else if (entry.isDirectory) {
            const reader = entry.createReader();
            while (true) {
                const batch = await new Promise(resolve => reader.readEntries(resolve));
                if (!batch || batch.length === 0) break;
                for (const e of batch) {
                    await walk(e, path + entry.name + "/");
                }
            }
        }
    }

    for (const e of entries) {
        await walk(e, "");
    }
}

/**
 * Traverse FileList/Array<File> (from <input webkitdirectory>)
 * Calls onFile(relativePath, file) for each file with webkitRelativePath.
 */
async function traverseFileList(fileList, onFile) {
    for (const f of Array.from(fileList || [])) {
        if (!f.webkitRelativePath) continue; // only folder-picked files
        if (f.size === 0) continue;
        await onFile(f.webkitRelativePath, f);
    }
}

// ------------------------ main DnD setup ------------------------

async function initDragnDrop() {
    const dropZone = document.getElementById("drop-zone");
    if (!dropZone) {
        console.warn("Drop zone element not found.");
        return;
    }

    // Prevent default browser behavior + stop propagation
    ["dragenter", "dragover", "dragleave", "drop"].forEach(ev => {
        dropZone.addEventListener(ev, e => { e.preventDefault(); e.stopPropagation(); });
    });

    // Simple visual feedback
    dropZone.addEventListener("dragover", () => dropZone.classList.add("dragging"));
    dropZone.addEventListener("dragleave", () => dropZone.classList.remove("dragging"));

    dropZone.addEventListener("drop", async (e) => {
        dropZone.classList.remove("dragging");

        const rootFolderId = getParameter("jbd");
        if (!rootFolderId || typeof rootFolderId !== "string") {
            throwError("Invalid destination folder.");
            return;
        }

        // Prepare helpers
        const ensurePath = makeEnsurer(getFolderLocation, uploadFolder, true);
        const uploadWithPath = async (relPath, file) => {
            const parentId = await ensurePath(rootFolderId, relPath);

            await uploadFile(file, parentId);
        };

        try {

            // 1) Upload loose files (not in folders)
            const loose = Array.from(e.dataTransfer.files || [])
                .filter(f => !f.webkitRelativePath && f.size > 0);
            for (const f of loose) {
                await uploadFile(f, rootFolderId);
            }

            // 2) Handle folders via DnD items
            if (e.dataTransfer.items && e.dataTransfer.items.length) {
                await traverseItems(e.dataTransfer.items, uploadWithPath);
            }
            // 3) Fallback for <input webkitdirectory> style drops (rare)
            else if (e.dataTransfer.files && e.dataTransfer.files.length) {
                await traverseFileList(e.dataTransfer.files, uploadWithPath);
            }

            // post-upload housekeeping
            if (typeof reloadStorageLimit === "function") await reloadStorageLimit();
            if (typeof refreshDirectoryDrive === "function") await refreshDirectoryDrive(rootFolderId);

        } catch (err) {
            console.error(err);
        }
    });
}

// Auto-init on DOM ready
document.addEventListener("DOMContentLoaded", () => {
    initDragnDrop().catch(err => {
        console.error("initDragnDrop failed:", err);
    });
});