import pygetwindow as gw  # Helps us find the game window and check if it's active
import mss  # Super fast library for grabbing screenshots (https://python-mss.readthedocs.io/)
import numpy as np  # We use this to turn images into math arrays so OpenCV can work with them
import cv2  # OpenCV: our workhorse for image tweaks (resizing, black-and-white, etc.)
import ddddocr  # The local AI that actually reads the text from our screenshots
import requests  # Needed to talk to the Firebase database and push our data
import time  # For pacing the script and calculating timestamps
import os  # Handy for checking file paths and cleanly exiting the app
import sys  # Lets us mess with Python internals (like routing console output to our GUI)
import tkinter as tk  # Python's built-in GUI toolkit. We use it for our hidden console window
from tkinter import simpledialog, messagebox  # For those pop-up boxes asking for the PIN and showing errors
import threading  # Crucial for letting the scanner run in the background without freezing the UI
import pystray  # This is what puts our little icon in the Windows system tray
from PIL import Image  # Pillow: used by pystray to handle the .ico image file
import winreg  # We need this to talk to Windows and say "Hey, start me up when the PC turns on"

## Core settings
FIREBASE_URL = "https://uma-widget-default-rtdb.europe-west1.firebasedatabase.app/"
# Fire up the OCR engine right away. show_ad=False keeps the console output clean.
ocr_reader = ddddocr.DdddOcr(show_ad=False)

## Global Controls
# This is our kill switch. As long as it's True, the background scanner keeps looping.
app_running = True
USER_ID = None

## Windows startup logic
STARTUP_KEY_NAME = "UmaWidgetScanner"


def is_in_startup():
    """Check if we're already set to boot with Windows."""
    try:
        # Peek into the Windows registry where startup apps live
        key = winreg.OpenKey(winreg.HKEY_CURRENT_USER, r'Software\Microsoft\Windows\CurrentVersion\Run', 0, winreg.KEY_READ)
        winreg.QueryValueEx(key, STARTUP_KEY_NAME)
        winreg.CloseKey(key)
        return True  # Yep, we're in there!
    except FileNotFoundError:
        return False  # Nope, not found.


def toggle_startup(icon, item):
    """Flips the 'Start with Windows' setting on or off when the user clicks it in the menu."""
    key = winreg.OpenKey(winreg.HKEY_CURRENT_USER, r'Software\Microsoft\Windows\CurrentVersion\Run', 0, winreg.KEY_SET_VALUE)

    if is_in_startup():
        # User wants us out, so we delete our registry key
        try:
            winreg.DeleteValue(key, STARTUP_KEY_NAME)
            print("[-] Removed from Windows startup.")
        except FileNotFoundError:
            pass
    else:
        # User wants us to start automatically. We give Windows the exact path to this .exe
        exe_path = f'"{sys.executable}"'
        winreg.SetValueEx(key, STARTUP_KEY_NAME, 0, winreg.REG_SZ, exe_path)
        print("[+] Added to Windows startup.")

    winreg.CloseKey(key)


## The "fake" console
class ThreadSafeConsole:
    """
    Because our scanner runs in the background (a separate thread), it's not allowed
    to update the GUI directly. If it tries, the app crashes.
    This class catches all normal print() statements and safely hands them over to the GUI thread.
    """

    def __init__(self, text_widget, root):
        self.text_widget = text_widget
        self.root = root

    def write(self, string):
        # Tell the main Tkinter window to handle this text whenever it has a free moment
        self.root.after(0, self._write, string)

    def _write(self, string):
        # Unlock the text box, dump the text in, auto-scroll to the bottom, and lock it back up
        self.text_widget.config(state=tk.NORMAL)
        self.text_widget.insert(tk.END, string)
        self.text_widget.see(tk.END)
        self.text_widget.config(state=tk.DISABLED)

    def flush(self):
        # Python expects this method to exist when hijacking sys.stdout, so we just pass.
        pass


## User initialization
def get_user_id(root_window):
    """
    Reads the user ID from the local config, or pops up a UI to create one if it's their first time.
    """
    # 1. See if they've been here before
    if os.path.exists("user_config.txt"):
        with open("user_config.txt", "r") as f:
            return f.read().strip()

    # 2. If not, let's start the setup wizard
    while True:
        # Ask for the player name
        username = simpledialog.askstring("Initial Setup", "Please enter your player name:", parent=root_window)
        if not username:
            os._exit(0)  # User chickened out, close the app

        # 3. Make sure they give us exactly a 4-digit number for the PIN
        while True:
            pin_code = simpledialog.askstring("Initial Setup", "Enter a 4-digit PIN code (ex: 1234):", parent=root_window)
            if not pin_code:
                os._exit(0)

            pin_code = pin_code.strip()

            # Strict check: 4 characters, and all must be numbers
            if len(pin_code) == 4 and pin_code.isdigit():
                break
            else:
                messagebox.showerror("Invalid Input", "The PIN code must be exactly 4 numeric digits.\nPlease try again.", parent=root_window)

        full_id = f"{username.strip()}-{pin_code}"
        check_url = f"{FIREBASE_URL}users/{full_id}.json"

        # 4. Check with Firebase to see if someone is already using this ID
        try:
            response = requests.get(check_url, timeout=10)
            data = response.json()

            if data is None:
                # Awesome, it's free. Let's save it so they don't have to do this again.
                with open("user_config.txt", "w") as f:
                    f.write(full_id)
                messagebox.showinfo("Success", f"Profile successfully created for:\n{full_id}", parent=root_window)
                return full_id
            else:
                # Oops, taken. Send them back to the start.
                messagebox.showerror("Error",
                                     f"The ID '{full_id}' is already taken.\nPlease choose another username or PIN code.", parent=root_window)

        except requests.exceptions.RequestException:
            # Handle the "no internet" scenario gracefully
            messagebox.showwarning("Network Error", "Cannot connect to Firebase.\nPlease check your internet connection.", parent=root_window)
            time.sleep(2)


## Cloud sync
def sync_to_cloud(user_id, tp_value, rp_value, karats_value, custom_tp_time=None, custom_rp_time=None):
    """Pushes our freshly scraped data up to the cloud."""
    url_users = f"{FIREBASE_URL}users/{user_id}.json"
    current_time = int(time.time())

    # Build the payload we want to send
    payload = {
        "tp": tp_value,
        "rp": rp_value,
        "karats": karats_value,
        # If we successfully calculated a "time travel" timestamp, use it. Otherwise, fallback to right now.
        "last_update_tp": custom_tp_time if custom_tp_time else current_time,
        "last_update_rp": custom_rp_time if custom_rp_time else current_time
    }

    try:
        # We use PATCH instead of PUT so we only update these specific keys.
        # We don't want to accidentally wipe out their custom widget settings in the database!
        requests.patch(url_users, json=payload, timeout=10)
    except requests.exceptions.RequestException:
        print("⚠ Internet error during cloud sync!")


## Ocr and Image magic
def extract_numbers_from_image(image_crop):
    """Takes a tiny slice of the screen, cleans it up, and asks the AI to read the numbers."""
    height, width = image_crop.shape[:2]

    # 1. I put this line here in case there is issues with the numbers to make them bigger so the AI doesn't have to squint ( just change the 1)
    zoomed_img = cv2.resize(image_crop, (width * 1, height * 1), interpolation=cv2.INTER_CUBIC)

    # 2. Strip the color out. Color just confuses the OCR.
    grayscale_img = cv2.cvtColor(zoomed_img, cv2.COLOR_BGR2GRAY)

    # 3. Force the image into pure black and white (high contrast)
    _, threshold_img = cv2.threshold(grayscale_img, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)

    # 4. Invert it. The AI was trained to read black text on a white page.
    inverted_img = cv2.bitwise_not(threshold_img)

    # 5. Package it back into bytes so ddddocr can digest it
    _, buffer = cv2.imencode('.png', inverted_img)
    image_bytes = buffer.tobytes()

    # 6. Let the AI do its thing
    extracted_text = ocr_reader.classification(image_bytes)

    # 7. The AI sometimes hallucinates (like thinking a '0' is an 'O'). Let's fix common typos.
    cleaned_text = extracted_text.replace('O', '0').replace('o', '0').replace('B', '8').replace('b', '8').replace('l', '1').replace('I', '1')

    # 8. Finally, scrub out any stray letters or symbols. We only want numbers.
    digits_only = ''.join(char for char in cleaned_text if char.isdigit())

    return digits_only


## Window hunting
def get_game_window():
    """Hunts for the game window and gets its coordinates."""
    windows = gw.getWindowsWithTitle('Umamusume')
    if windows:
        win = windows[0]
        # If the game is minimized or in the background, it visually pauses the timers.
        # We tell the scanner to hold off if that happens.
        if not win.isActive:
            return "PAUSED"
        return {"top": win.top, "left": win.left, "width": win.width, "height": win.height}
    return None


## The main HEARTBEAT (OGURI CAP BRIGHTEST HEATBEAT)
def main_loop():
    """This is the heartbeat of the app. It runs endlessly in the background."""
    global app_running
    print("SCRIPT LAUNCHED. Scanning...")
    print("Fetching cloud memory...")

    # Grab the last known numbers from the cloud.
    # This stops us from accidentally overwriting good data with empty timers if the script restarts.
    try:
        response = requests.get(f"{FIREBASE_URL}users/{USER_ID}.json", timeout=10)
        data = response.json()
        if data:
            last_tp = str(data.get("tp", ""))
            last_rp = str(data.get("rp", ""))
            last_karats = str(data.get("karats", ""))
        else:
            last_tp, last_rp, last_karats = None, None, None
    except (requests.exceptions.RequestException, ValueError):
        last_tp, last_rp, last_karats = None, None, None

    # Keep looping until someone pulls the plug (app_running = False)
    while app_running:
        window_rect = get_game_window()

        if not window_rect:
            print("⮽ Game window not found. Retrying in 30s...")
            # Responsive sleeping: Instead of one massive 30-second sleep, we do 30 one-second sleeps.
            # This way, if the user hits "Quit", the app closes instantly without waiting.
            for _ in range(30):
                if not app_running: return
                time.sleep(1)
            continue

        if window_rect == "PAUSED":
            print("Umamusume is running in the background, Waiting...")
            for _ in range(10):
                if not app_running: return
                time.sleep(1)
            continue

        try:
            with mss.mss() as sct:
                # Math out the exact play area. We don't want to scan the Windows title bar or borders.
                win_w, win_h = window_rect["width"], window_rect["height"]
                top_offset, side_offset = (32, 8) if window_rect["top"] != 0 else (0, 0)
                view_width = win_w - (side_offset * 2)
                view_height = win_h - top_offset - side_offset

                # Say cheese!
                screen_capture = sct.grab(window_rect)
                game_image = np.array(screen_capture)[:, :, :3]

                # Slice off the Windows borders so we're only looking at the game itself
                game_image = game_image[top_offset: win_h - side_offset, side_offset: win_w - side_offset]

                # --- THE SNIPER CROPS ---
                # These percentages are hardcoded to the standard Umamusume UI layout.
                tp_crop = game_image[
                    int(view_height * 0.029):int(view_height * 0.045), int(view_width * 0.245):int(view_width * 0.266)]
                rp_crop = game_image[
                    int(view_height * 0.03):int(view_height * 0.045), int(view_width * 0.36):int(view_width * 0.368)]
                karats_crop = game_image[
                    int(view_height * 0.077):int(view_height * 0.089), int(view_width * 0.32):int(view_width * 0.355)]

                # These are the tiny little timers underneath the main stamina bars
                tp_timer_crop = game_image[
                    int(view_height * 0.029):int(view_height * 0.045), int(view_width * 0.220):int(view_width * 0.240)]
                rp_timer_crop = game_image[
                    int(view_height * 0.030):int(view_height * 0.045), int(view_width * 0.328):int(view_width * 0.355)]

                # Feed the cropped images to our OCR function
                current_tp = extract_numbers_from_image(tp_crop)
                current_rp = extract_numbers_from_image(rp_crop)
                current_karats = extract_numbers_from_image(karats_crop)

                # Only try to read the timers if we actually captured something there
                current_tp_timer = extract_numbers_from_image(tp_timer_crop) if tp_timer_crop.size > 0 else ""
                current_rp_timer = extract_numbers_from_image(rp_timer_crop) if rp_timer_crop.size > 0 else ""

                # Did we actually read a number? Let's check.
                if current_tp.isdigit():

                    # Only bother the database if the numbers actually changed since the last loop
                    if current_tp != last_tp or current_rp != last_rp or current_karats != last_karats:
                        tp_timestamp = None
                        rp_timestamp = None

                        ## Time travel magic
                        # If we can see the countdown timer, we do some math to figure out the exact
                        # Unix timestamp when the energy will be 100% full. This lets the mobile widget
                        # keep counting down accurately even when this PC script is turned off!

                        # TP refills every 10 minutes (600 seconds)
                        if len(current_tp_timer) >= 3:
                            try:
                                remaining_seconds = int(current_tp_timer[-2:])
                                remaining_minutes = int(current_tp_timer[:-2])
                                total_remaining_seconds = (remaining_minutes * 60) + remaining_seconds
                                tp_timestamp = int(time.time()) - (600 - total_remaining_seconds)
                            except ValueError:
                                pass

                        # RP refills every 2 hours (7200 seconds)
                        if len(current_rp_timer) >= 5:
                            try:
                                remaining_seconds = int(current_rp_timer[-2:])
                                remaining_minutes = int(current_rp_timer[-4:-2])
                                remaining_hours = int(current_rp_timer[:-4])
                                total_remaining_seconds = (remaining_hours * 3600) + (remaining_minutes * 60) + remaining_seconds
                                rp_timestamp = int(time.time()) - (7200 - total_remaining_seconds)
                            except ValueError:
                                pass

                        # Send it all up to Firebase
                        sync_to_cloud(USER_ID, current_tp, current_rp, current_karats, custom_tp_time=tp_timestamp, custom_rp_time=rp_timestamp)

                        # Print a nice log to our console
                        log_time = time.strftime('%H:%M:%S')
                        log_tp_timer = current_tp_timer if current_tp_timer else '--'
                        log_rp_timer = current_rp_timer if current_rp_timer else '--'
                        print(
                            f"🗸 Sync {log_time} | TP: {current_tp} RP: {current_rp} KR: {current_karats} | TP Timer: {log_tp_timer} | RP Timer: {log_rp_timer}")

                        # Update our local memory so we don't spam the database on the next loop
                        last_tp = current_tp
                        last_rp = current_rp
                        last_karats = current_karats
                    else:
                        print(f"[{time.strftime('%H:%M:%S')}] No changes detected. Sync skipped.")
                else:
                    print("⚠ Can't read UI or not in the main menu...")

        except Exception as e:
            print(f"⮽ Error while scanning: {e}")

        # Chill out for a minute before scanning again (broken up for responsiveness)
        for _ in range(60):
            if not app_running: return
            time.sleep(1)


## System tray functions
def show_window(icon=None, item=None):
    """Brings the console back from the dead when they double-click the tray icon."""
    gui_root.after(0, gui_root.deiconify)


def hide_window():
    """We don't actually close the app when they hit the 'X', we just hide it in the tray."""
    gui_root.withdraw()


def quit_application(icon=None, item=None):
    """Pulls the plug. Stops the background loop, kills the tray icon, and nukes the UI."""
    global app_running
    app_running = False
    if icon:
        icon.stop()
    gui_root.after(0, gui_root.destroy)
    os._exit(0)


## Entry point
if __name__ == "__main__":

    # When Windows launches this app on boot, it starts it from the System32 folder.
    # This completely breaks our file paths. This chunk forces the script to pretend
    # it was launched from its actual home folder so we don't get 'Permission Denied' errors.
    if getattr(sys, 'frozen', False):
        # We are running as a compiled .exe
        application_path = os.path.dirname(sys.executable)
    else:
        # We are running as a raw .py script
        application_path = os.path.dirname(os.path.abspath(__file__))

    os.chdir(application_path)

    # 1. Set up the ui
    # We build our hacker-style terminal window here
    gui_root = tk.Tk()
    gui_root.title("Uma-Widget Scanner Console")
    gui_root.geometry("680x400")
    gui_root.configure(bg="#0c0c0c")

    # Let's try to load the taskbar icon (the one on the bottom of the screen)
    taskbar_icon_filename = "icon.ico"
    if os.path.exists(taskbar_icon_filename):
        try:
            gui_root.wm_iconbitmap(taskbar_icon_filename)
            print(f"[+] Loaded taskbar icon: {taskbar_icon_filename}")
        except tk.TclError:
            print(f"[!] Error loading taskbar icon (probably a bad .ico file): {taskbar_icon_filename}")
    else:
        print(f"[!] Taskbar icon file not found: {taskbar_icon_filename}")

    # Override the 'X' button to run our hide_window function instead of quitting
    gui_root.protocol("WM_DELETE_WINDOW", hide_window)
    gui_root.withdraw()  # Start completely hidden

    # The actual text box where our logs will show up
    console_text = tk.Text(gui_root, bg="#0c0c0c", fg="#00FF00", font=("Consolas", 10), wrap="word")
    console_text.pack(expand=True, fill="both", padx=5, pady=5)

    # Hijack normal Python prints. From now on, print() writes to our custom window.
    sys.stdout = ThreadSafeConsole(console_text, gui_root)
    sys.stderr = sys.stdout

    # 2.Get the user ready
    USER_ID = get_user_id(gui_root)

    # Slap that beautiful ASCII art right into the terminal
    print("\
    ██╗   ██╗███╗   ███╗ █████╗       ██╗    ██╗██╗██████╗  ██████╗ ███████╗████████╗\n\
    ██║   ██║████╗ ████║██╔══██╗      ██║    ██║██║██╔══██╗██╔════╝ ██╔════╝╚══██╔══╝\n\
    ██║   ██║██╔████╔██║███████║█████╗██║ █╗ ██║██║██║  ██║██║  ███╗█████╗     ██║   \n\
    ██║   ██║██║╚██╔╝██║██╔══██║╚════╝██║███╗██║██║██║  ██║██║   ██║██╔══╝     ██║   \n\
    ╚██████╔╝██║ ╚═╝ ██║██║  ██║      ╚███╔███╔╝██║██████╔╝╚██████╔╝███████╗   ██║   \n\
     ╚═════╝ ╚═╝     ╚═╝╚═╝  ╚═╝       ╚══╝╚══╝ ╚═╝╚═════╝  ╚═════╝ ╚══════╝   ╚═╝  ")
    print(f"\nWelcome back, {USER_ID}!\n")

    # --- 3. THE TRAY ICON ---
    # Load the tiny icon for the system tray (next to the clock)
    logo_filename = "icon.ico"
    try:
        if not os.path.exists(logo_filename):
            tray_image = Image.new('RGB', (64, 64), color=(41, 128, 185))  # Fallback: a boring blue square
        else:
            tray_image = Image.open(logo_filename)
    except Exception:
        tray_image = Image.new('RGB', (64, 64), color=(41, 128, 185))

    # --- 4. FIRE UP THE SCANNER ---
    # Start the scanner in its own thread. daemon=True means it'll die cleanly if the main app crashes.
    scan_thread = threading.Thread(target=main_loop)
    scan_thread.daemon = True
    scan_thread.start()

    # --- 5. SETUP THE TRAY MENU ---
    menu = pystray.Menu(
        pystray.MenuItem('Show Console', show_window, default=True),
        # This checkbox magically updates itself depending on what is_in_startup() says
        pystray.MenuItem('Start with Windows', toggle_startup, checked=lambda item: is_in_startup()),
        pystray.MenuItem('Quit', quit_application)
    )
    tray_icon = pystray.Icon("UmamusumeScanner", tray_image, "Uma-Widget Scanner", menu)

    # Start the tray icon's own loop (detached so it doesn't freeze us)
    tray_icon.run_detached()

    # --- 6. KEEP THE LIGHTS ON ---
    # Start the main Tkinter loop. This keeps the whole party going.
    gui_root.mainloop()