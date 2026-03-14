// Configuration & DB import
const FIREBASE_BASE_URL = "https://uma-widget-default-rtdb.europe-west1.firebasedatabase.app/users/";
let currentUser = null;

// Login and Logout
function checkUser() {
    // We read memory only if the user is unknown
    if (!currentUser) {
        const urlParams = new URLSearchParams(window.location.search);
        currentUser = urlParams.get('user') || localStorage.getItem('uma_user');
    }

    const loginScreen = document.getElementById('login-screen');
    const widgetScreen = document.querySelector('.widget');

    if (!currentUser) {
        loginScreen.style.display = 'flex';
        widgetScreen.style.display = 'none';
    } else {
        loginScreen.style.display = 'none';
        widgetScreen.style.display = 'block';
        
        localStorage.setItem('uma_user', currentUser);
        document.title = `Uma Widget - ${currentUser}`;
        
        fetchData();
    }
}

function saveUser() {
    const pseudoInput = document.getElementById('username-input');
    const pinInput = document.getElementById('pin-input');
    
    const pseudo = pseudoInput ? pseudoInput.value.trim() : "";
    const pin = pinInput ? pinInput.value.trim() : "";
    
    if (pseudo !== "" && pin !== "") {
        const tempUser = `${pseudo}-${pin}`; 
        
        // Ask firebase if the acc exists
        const checkUrl = `${FIREBASE_BASE_URL}${tempUser}.json`;
        
        fetch(checkUrl)
            .then(response => response.json())
            .then(data => {
                if (data !== null) {
                    // The account exists
                    currentUser = tempUser;
                    checkUser(); // Launch widget
                } else {
                    // the acc isn't in the firebase
                    alert("⮽ Username or password incorrect or does not exists!");
                }
            })
            .catch(error => {
                console.error("Erreur de vérification:", error);
                alert("⚠  Network error! Can't check if login exist.");
            });
            
    } else {
        alert("Veuillez entrer un pseudo ET un code PIN !");
    }
}
function logoutUser() {
    localStorage.removeItem('uma_user');
    currentUser = null;
    window.history.replaceState({}, document.title, window.location.pathname);
    checkUser();
}
function changeBackground() {
    const newUrl = prompt("Paste background link (ex: Imgur, Discord, Pintrest, etc.) :\nIf you want to go back to default put nothing");
    
    // If the user did not paste anything
    if (newUrl !== null) { 
        const widget = document.querySelector('.widget');
        
        if (newUrl.trim() === "") {
            // Default image
            widget.style.backgroundImage = "url('img/background.jpg')";
        } else {
            // Apply new image
            widget.style.backgroundImage = `url('${newUrl}')`;
        }

        // Save image link in firebase
        const finalUrl = `${FIREBASE_BASE_URL}${currentUser}.json`;
        fetch(finalUrl, {
            method: 'PATCH',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ bg_url: newUrl })
        }).catch(err => console.error("Erreur de sauvegarde du fond :", err));
    }
}
// Event litsener
document.addEventListener('DOMContentLoaded', () => {
    const inputField = document.getElementById('username-input');
    const pinField = document.getElementById('pin-input');
    const btnGo = document.getElementById('btn-go'); // go button
    const btnLogout = document.getElementById('btn-logout'); // logout button
    const btnBg = document.getElementById('btn-bg'); // // background button

    
    // Clicks
    if (btnGo) btnGo.addEventListener('click', saveUser);
    if (btnLogout) btnLogout.addEventListener('click', logoutUser);
    if (btnBg) btnBg.addEventListener('click', changeBackground);
    // Enter key
    const triggerEnter = function (e) {
        if (e.key === 'Enter') saveUser();
    };
    if (inputField) inputField.addEventListener('keypress', triggerEnter);
    if (pinField) pinField.addEventListener('keypress', triggerEnter);
});

// Operations and calender
function updateCalendar() {
    const now = new Date();
    const optionsDay = { weekday: 'short' }; 
    const dayName = new Intl.DateTimeFormat('en-US', optionsDay).format(now);
    
    const month = String(now.getMonth() + 1).padStart(2, '0');
    const day = String(now.getDate()).padStart(2, '0');
    
    document.getElementById('day-name').textContent = dayName;
    document.getElementById('date-num').textContent = `${month}/${day}`;
}

function calculateRegen(savedTP, savedRP, lastUpdateTimestamp) {
    const nowTimestamp = Math.floor(Date.now() / 1000); 
    const secondsElapsed = nowTimestamp - lastUpdateTimestamp;

    // Calcul TP
    const tpRegenTime = 600; 
    const tpGained = Math.floor(secondsElapsed / tpRegenTime);
    let currentTP = parseInt(savedTP) + tpGained;
    
    if (currentTP >= 100) {
        currentTP = 100;
        document.getElementById('sub-tp').textContent = "Max";
    } else {
        const secondsToNextTP = tpRegenTime - (secondsElapsed % tpRegenTime);
        const minutesToNextTP = Math.floor(secondsToNextTP / 60);
        document.getElementById('sub-tp').textContent = `+1 in ${minutesToNextTP}m`;
    }

    // Calcul RP
    const rpRegenTime = 7200;
    const rpGained = Math.floor(secondsElapsed / rpRegenTime);
    let currentRP = parseInt(savedRP) + rpGained;

    if (currentRP >= 5) {
        currentRP = 5;
        document.getElementById('sub-rp').textContent = "Max";
    } else {
        const secondsToNextRP = rpRegenTime - (secondsElapsed % rpRegenTime);
        const hoursToNextRP = Math.floor(secondsToNextRP / 3600);
        const minutesToNextRP = Math.floor((secondsToNextRP % 3600) / 60);
        
        if (hoursToNextRP > 0) {
            document.getElementById('sub-rp').textContent = `+1 in ${hoursToNextRP}h ${minutesToNextRP}m`;
        } else {
            document.getElementById('sub-rp').textContent = `+1 in ${minutesToNextRP}m`;
        }
    }

    document.getElementById('val-tp').textContent = currentTP;
    document.getElementById('val-rp').textContent = currentRP;
}

// Data collection
function fetchData() {
    if (!currentUser) return;

    const finalUrl = `${FIREBASE_BASE_URL}${currentUser}.json`;

    fetch(finalUrl)
        .then(response => {
            if (!response.ok) throw new Error(`HTTP error! status: ${response.status}`);
            return response.json();
        })
        .then(data => {
            if (data) {
                document.getElementById('val-karats').textContent = data.karats;

                if (data.bg_url && data.bg_url.trim() !== "") {
                    document.querySelector('.widget').style.backgroundImage = `url('${data.bg_url}')`;
                } else {
                    document.querySelector('.widget').style.backgroundImage = "url('img/background.jpg')";
                }

                const dateSync = new Date(data.last_update * 1000);
                const timeString = dateSync.toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit' });
                document.getElementById('last-update-text').textContent = `Sync: ${timeString}`;

                calculateRegen(data.tp, data.rp, data.last_update);
            } else {
                document.getElementById('last-update-text').textContent = "Waiting for game...";
            }
        })
        .catch(error => {
            console.error("Error fetching data:", error);
            document.getElementById('last-update-text').textContent = "Offline / Not Found";
        });
}

// Initialisation
updateCalendar();
checkUser();

setInterval(() => {
    if (currentUser) {
        updateCalendar();
        fetchData();
    }
}, 10000);
