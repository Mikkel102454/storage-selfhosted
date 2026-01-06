let folderModalTemplate = document.getElementById("folderModal")
let currentFolderModal

function openFolderModal(){
    if(!folderModalTemplate){throwError("folderModalTemplate was not set"); return}
    if(currentFolderModal){throwError("folderModal already open"); return}

    let clone = folderModalTemplate.content.cloneNode(true)

    clone.firstElementChild.id = "folderModalOpen"

    let nameInput = clone.querySelector('[typeI="input.name"]')

    clone.querySelector('[type="button.confirm"]').addEventListener("click", async () => {
        closeFolderModal()
        await uploadFolder(getParameter("jbd"), nameInput.value, true)
    })

    clone.querySelector('[type="button.cancel"]').addEventListener("click", async () => {
        closeFolderModal()
    })

    clone.querySelector('[type="modal.background"]').addEventListener("click", async () => {
        closeFolderModal()
    })

    getModalParent().appendChild(clone)
    currentFolderModal = document.getElementById("folderModalOpen")
}

function closeFolderModal(){
    if(!currentFolderModal) {console.log("folderModal was not open"); return}
    currentFolderModal.remove()
    currentFolderModal = null
}

document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape') closeFolderModal();
});
