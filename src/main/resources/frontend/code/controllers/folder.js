async function getFolderLocation(folderUuid){
    const response = await fetch(`/api/folders/location?folderUuid=${encodeURIComponent(folderUuid)}`, {
        method: "GET"
    });

    if (!response.ok) {
        return handleServerReturnAlert(response.status, await response.text())
    }

    const result = await response.json();

    return result.map(folder => new Folder(
        folder.uuid,
        folder.owner,
        folder.name,
        folder.folderId,
        folder.size
    ));
}

async function uploadFolder(folderId, folderName, show = false) {
    const response = await fetch(`/api/folders/upload?folderId=${encodeURIComponent(folderId)}&folderName=${encodeURIComponent(folderName)}`, {
        method: "POST"
    });

    if(getHandleServerReturnType(response.status) !== true) return handleServerReturnAlert(response.status, await response.text());

    const result = await response.json();

    let folder = new Folder(
        result.uuid,
        result.owner,
        result.name,
        result.folderId,
        result.size
    );

    if (show) await folder.load(viewContainerDrive)

    return folder.uuid
}

async function browseDirectory(folderId) {
    const response = await fetch(`/api/folders/browse?folderId=${encodeURIComponent(folderId)}`, {
        method: "GET"
    });

    if (!response.ok) {
        handleServerReturnAlert(response.status, await response.text())
        return;
    }

    const result = await response.json();

    const files = result.files.map(file => new File(
        file.uuid,
        file.owner,
        file.name,
        file.extension,
        file.folderId,
        file.created,
        file.modified,
        file.accessed,
        file.size,
        file.starred
    ));

    const folders = result.folders.map(folder => new Folder(
        folder.uuid,
        folder.owner,
        folder.name,
        folder.folderId,
        folder.size
    ));

    return { files, folders };
}

function downloadZipFile(folderId, folderUuid) {
    fetch(`/api/folders/download?folderId=${encodeURIComponent(folderId)}&folderUuid=${encodeURIComponent(folderUuid)}`, {
        method: "GET"
    })
        .then(async response => {
            if(response.status === 404) {throwWarning("No files found to zip"); return Promise.reject("No files found to zip");}
            if (!response.ok) throw new Error("Download failed");

            // Try to get filename from the Content-Disposition header
            const disposition = response.headers.get("Content-Disposition");
            let filename = "downloaded_file";
            if (disposition && disposition.includes("filename=")) {
                const match = disposition.match(/filename="(.+)"/);
                if (match && match[1]) filename = match[1];
            }

            return response.blob().then(blob => ({ blob, filename }));
        })
        .then(({ blob, filename }) => {
            const link = document.createElement("a");
            link.href = window.URL.createObjectURL(blob);
            link.download = filename;
            link.click();
            window.URL.revokeObjectURL(link.href);
        })
        .catch(error => {
            if(error !== "No files found to zip"){
                throwError(error)
            }
        });
}

async function deleteFolder(folderId, folderUuid, notify) {
    const response = await fetch(`/api/folders/delete?folderId=${encodeURIComponent(folderId)}&folderUuid=${encodeURIComponent(folderUuid)}`, {
        method: "POST"
    });

    if(getHandleServerReturnType(response.status)) return handleServerReturnAlert(response.status, await response.text())
    return true
}

async function renameFolder(folderId, folderUuid, name){
    const response = await fetch(`/api/folders/rename?folderId=${encodeURIComponent(folderId)}&folderUuid=${encodeURIComponent(folderUuid)}&name=${encodeURIComponent(name)}`, {
        method: "POST"
    });

    return handleServerReturnAlert(response.status, await response.text())
}

async function getParentFolder(folderId, folderName){
    const response = await fetch(`/api/folders/parent?folderId=${encodeURIComponent(folderId)}&folderName=${encodeURIComponent(folderName)}`, {
        method: "GET"
    });

    if (!response.ok) {
        //handleServerReturnAlert(response.status, await response.text())
        return null;
    }

    const result = await response.json()

    return new Folder(
        result.uuid,
        result.owner,
        result.name,
        result.folderId,
        result.size
    );
}

async function FolderUploading(filesList) {
    const files = Array.from(filesList);
    console.log(files)
    const rootFolderId = getParameter("jbd");

    if (!rootFolderId || typeof rootFolderId !== "string") {
        return;
    }

    const folderIdCache = new Map();
    for (const file of files) {
        if (!file.webkitRelativePath) continue; // skip loose files

        const parts = file.webkitRelativePath.split('/');
        parts.pop(); // Remove file name from path
        let parentId = rootFolderId;

        for (let i = 0; i < parts.length; i++) {
            const folderName = parts[i];
            const cacheKey = `${parentId}/${folderName}`;

            if (!folderIdCache.has(cacheKey)) {
                let folderId;

                if (i === 0) {
                    // Always create the top-level folder (e.g., "MyFolder")
                    folderId = await uploadFolder(parentId, folderName, true);
                } else {
                    // Try to fetch, or create if missing
                    folderId = await getParentFolder(parentId, folderName).uuid;
                    if (!folderId || typeof folderId !== "string") {
                        folderId = await uploadFolder(parentId, folderName);
                    }
                }

                folderIdCache.set(cacheKey, folderId);
            }

            parentId = folderIdCache.get(cacheKey);
            console.log(parentId)
        }
        await refreshDirectoryDrive()
        await uploadFile(file, parentId);
    }
    await refreshDirectoryDrive();
}

async function moveFolder(itemId, folderId, newFolderId){
    if (itemId === newFolderId) return false
    if (folderId === newFolderId) return false
    const response = await fetch(`/api/folders/move?itemId=${encodeURIComponent(itemId)}&newFolderId=${encodeURIComponent(newFolderId)}`, {
        method: "PUT"
    });

    return handleServerReturnAlert(response.status, await response.text())
}

function openFolderUploadMenu() {
    document.getElementById('hiddenFolderInput').click();
}

document.getElementById('hiddenFolderInput').addEventListener('change', async function () {
    await FolderUploading(this.files)
    await reloadStorageLimit()
});