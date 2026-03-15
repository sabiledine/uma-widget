// Configuration & DB import
const FIREBASE_BASE_URL = "https://uma-widget-default-rtdb.europe-west1.firebasedatabase.app/users/";
let currentUser = null;

// --- AUTHENTICATION & UI MANAGEMENT ---

function checkUserSession() {
    // Read local memory if the user is unknown in the current session
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
        
        fetchDataFromCloud();
    }
}

function handleLogin() {
    const usernameInput = document.getElementById('username-input');
    const pinInput = document.getElementById('pin-input');
    
    const username = usernameInput ? usernameInput.value.trim() : "";
    const pinCode = pinInput ? pinInput.value.trim() : "";
    
    if (username !== "" && pinCode !== "") {
        const tempUserId = `${username}-${pinCode}`; 
        
        // Ask Firebase if the account exists
        const checkUrl = `${FIREBASE_BASE_URL}${tempUserId}.json`;
        
        fetch(checkUrl)
            .then(response => response.json())
            .then(data => {
                if (data !== null) {
                    // The account exists, log the user in
                    currentUser = tempUserId;
                    checkUserSession(); 
                } else {
                    alert("⮽ Username or pin code incorrect or does not exist!");
                }
            })
            .catch(error => {
                console.error("Verification error:", error);
                alert("⚠ Network error! Cannot check if login exists.");
            });
            
    } else {
        alert("Please enter a username AND a pin code!");
    }
}

function handleLogout() {
    localStorage.removeItem('uma_user');
    currentUser = null;
    
    // Clean up the URL parameters if any
    window.history.replaceState({}, document.title, window.location.pathname);
    checkUserSession();
}

function changeBackground() {
    const newBackgroundUrl = prompt("Paste background link (ex: Imgur, Discord, Pinterest, etc.) :\nLeave empty to revert to default.");
    
    // If the user didn't cancel the prompt
    if (newBackgroundUrl !== null) { 
        const widgetContainer = document.querySelector('.widget');
        
        if (newBackgroundUrl.trim() === "") {
            // Revert to default local image
            widgetContainer.style.backgroundImage = "url('img/background.jpg')";
        } else {
            // Apply new custom image
            widgetContainer.style.backgroundImage = `url('${newBackgroundUrl}')`;
        }

        // Save the new image link in Firebase
        const updateUrl = `${FIREBASE_BASE_URL}${currentUser}.json`;
        fetch(updateUrl, {
            method: 'PATCH',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ bg_url: newBackgroundUrl })
        }).catch(err => console.error("Error saving background:", err));
    }
}

// --- EVENT LISTENERS ---

document.addEventListener('DOMContentLoaded', () => {
    const inputField = document.getElementById('username-input');
    const pinField = document.getElementById('pin-input');
    
    const btnLogin = document.getElementById('btn-go'); 
    const btnLogout = document.getElementById('btn-logout'); 
    const btnBackground = document.getElementById('btn-bg'); 

    // Click events
    if (btnLogin) btnLogin.addEventListener('click', handleLogin);
    if (btnLogout) btnLogout.addEventListener('click', handleLogout);
    if (btnBackground) btnBackground.addEventListener('click', changeBackground);
    
    // Enter key support for login form
    const triggerEnterKey = function (e) {
        if (e.key === 'Enter') handleLogin();
    };
    if (inputField) inputField.addEventListener('keypress', triggerEnterKey);
    if (pinField) pinField.addEventListener('keypress', triggerEnterKey);
});

// --- WIDGET LOGIC & CALCULATIONS ---

function updateCalendarWidget() {
    const currentDate = new Date();
    
    // Format day (e.g., "Mon", "Tue")
    const optionsDay = { weekday: 'short' }; 
    const formattedDay = new Intl.DateTimeFormat('en-US', optionsDay).format(currentDate);
    
    // Format Month/Day (e.g., "05/12")
    const formattedMonth = String(currentDate.getMonth() + 1).padStart(2, '0');
    const formattedDateNum = String(currentDate.getDate()).padStart(2, '0');
    
    document.getElementById('day-name').textContent = formattedDay;
    document.getElementById('date-num').textContent = `${formattedMonth}/${formattedDateNum}`;
}

function calculateRegeneration(cloudData) {
    const currentUnixTime = Math.floor(Date.now() / 1000); 

    // --- TP CALCULATION ---
    // Fallback chain: TP specific update -> General update -> Right now
    const tpLastUpdate = cloudData.last_update_tp || cloudData.last_update || currentUnixTime;
    const secondsElapsedTP = Math.max(0, currentUnixTime - tpLastUpdate);
    
    const tpRegenTimeSeconds = 600; // 10 minutes per TP
    const tpGained = Math.floor(secondsElapsedTP / tpRegenTimeSeconds);
    let calculatedTP = parseInt(cloudData.tp) + tpGained;
    
    if (calculatedTP >= 100) {
        calculatedTP = 100;
        document.getElementById('sub-tp').textContent = "Max";
    } else {
        // Calculate remaining time for a full gauge (100)
        const totalSecondsNeededTP = (100 - parseInt(cloudData.tp)) * tpRegenTimeSeconds;
        let remainingSecondsTP = totalSecondsNeededTP - secondsElapsedTP;

        const hoursLeft = Math.floor(remainingSecondsTP / 3600);
        const minutesLeft = Math.floor((remainingSecondsTP % 3600) / 60);

        if (hoursLeft > 0) {
            document.getElementById('sub-tp').textContent = `${hoursLeft}h ${minutesLeft}m`;
        } else {
            document.getElementById('sub-tp').textContent = `${minutesLeft}m`;
        }
    }

    // --- RP CALCULATION ---
    const rpLastUpdate = cloudData.last_update_rp || cloudData.last_update || currentUnixTime;
    const secondsElapsedRP = Math.max(0, currentUnixTime - rpLastUpdate);
    
    const rpRegenTimeSeconds = 7200; // 2 hours per RP
    const rpGained = Math.floor(secondsElapsedRP / rpRegenTimeSeconds);
    let calculatedRP = parseInt(cloudData.rp) + rpGained;

    if (calculatedRP >= 5) {
        calculatedRP = 5;
        document.getElementById('sub-rp').textContent = "Max";
    } else {
        // Calculate remaining time for a full gauge (5)
        const totalSecondsNeededRP = (5 - parseInt(cloudData.rp)) * rpRegenTimeSeconds;
        let remainingSecondsRP = totalSecondsNeededRP - secondsElapsedRP;

        const hoursLeft = Math.floor(remainingSecondsRP / 3600);
        const minutesLeft = Math.floor((remainingSecondsRP % 3600) / 60);

        if (hoursLeft > 0) {
            document.getElementById('sub-rp').textContent = `${hoursLeft}h ${minutesLeft}m`;
        } else {
            document.getElementById('sub-rp').textContent = `${minutesLeft}m`;
        }
    }

    // Render calculated values to the DOM
    document.getElementById('val-tp').textContent = calculatedTP;
    document.getElementById('val-rp').textContent = calculatedRP;
}

// --- DATA FETCHING ---

function fetchDataFromCloud() {
    if (!currentUser) return;

    const dataUrl = `${FIREBASE_BASE_URL}${currentUser}.json`;

    fetch(dataUrl)
        .then(response => {
            if (!response.ok) throw new Error(`HTTP error! status: ${response.status}`);
            return response.json();
        })
        .then(cloudData => {
            if (cloudData) {
                // Render Karats
                document.getElementById('val-karats').textContent = cloudData.karats;

                // Handle Background image rendering
                if (cloudData.bg_url && cloudData.bg_url.trim() !== "") {
                    document.querySelector('.widget').style.backgroundImage = `url('${cloudData.bg_url}')`;
                } else {
                    document.querySelector('.widget').style.backgroundImage = "url('img/background.jpg')";
                }

                // Render latest sync timestamp in the UI
                const latestUpdateTimestamp = Math.max(cloudData.last_update_tp || 0, cloudData.last_update_rp || 0, cloudData.last_update || 0);
                if (latestUpdateTimestamp > 0) {
                    const syncDate = new Date(latestUpdateTimestamp * 1000);
                    const formattedTimeString = syncDate.toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit' });
                    document.getElementById('last-update-text').textContent = `Sync: ${formattedTimeString}`;
                }

                // Push all data to calculate real-time regeneration
                calculateRegeneration(cloudData);
            } else {
                document.getElementById('last-update-text').textContent = "Waiting for game...";
            }
        })
        .catch(error => {
            console.error("Error fetching data:", error);
            document.getElementById('last-update-text').textContent = "Offline / Not Found";
        });
}

// --- INITIALIZATION ---

updateCalendarWidget();
checkUserSession();

// Setup polling every 10 seconds
setInterval(() => {
    if (currentUser) {
        updateCalendarWidget();
        fetchDataFromCloud();
    }
}, 10000);
