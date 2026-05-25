// Fluence - Landing Page Interactions

document.addEventListener('DOMContentLoaded', () => {
    initSimulator();
    initSetupWizard();
    initNavbarScroll();
});

/* 1. Navbar Scroll Effect */
function initNavbarScroll() {
    const header = document.querySelector('header');
    window.addEventListener('scroll', () => {
        if (window.scrollY > 50) {
            header.style.backgroundColor = 'rgba(10, 10, 10, 0.75)';
            header.style.backdropFilter = 'blur(20px)';
            header.style.webkitBackdropFilter = 'blur(20px)';
            header.style.borderBottom = '1px solid rgba(255, 255, 255, 0.08)';
        } else {
            header.style.backgroundColor = 'transparent';
            header.style.backdropFilter = 'none';
            header.style.webkitBackdropFilter = 'none';
            header.style.borderBottom = 'none';
        }
    });
}

/* 2. Interactive Simulator State Machine */
function initSimulator() {
    const floatingBubble = document.getElementById('sim-bubble');
    const overlayPill = document.getElementById('sim-overlay-pill');
    const bottomVoiceBar = document.getElementById('sim-voice-bar');
    const voiceStatusText = document.getElementById('sim-voice-status');
    const notepadBody = document.getElementById('sim-notepad-body');
    const waveformPath = document.getElementById('sim-waveform-path');
    const waveformPathBg = document.getElementById('sim-waveform-path-bg');
    
    let isSimulating = false;
    let wavePhase = 0;
    let waveAmplitude = 0;
    let waveTargetAmplitude = 0;
    let animationId = null;
    
    // Initial content
    const originalText = 'Hello World!<br>Tap the floating bubble to start voice typing.';
    const transcriptionText = 'Fluence transcribes sentences in under a second with human-level accuracy.';
    
    notepadBody.innerHTML = originalText + '<span class="typing-cursor"></span>';

    // SVG Waveform Drawing loop
    function animateWave() {
        if (!isSimulating) return;
        
        // Interpolate amplitude smoothly
        waveAmplitude += (waveTargetAmplitude - waveAmplitude) * 0.1;
        
        const width = 160;
        const height = 32;
        const points = [];
        const pointsBg = [];
        
        // Generate points for double sine wave
        for (let x = 0; x <= width; x += 2) {
            // Main wave: sine wave with envelope function to pinch the edges
            const envelope = Math.sin((x / width) * Math.PI); 
            const y1 = (height / 2) + Math.sin(x * 0.09 + wavePhase) * waveAmplitude * envelope;
            // Back/secondary wave (shifted phase/frequency for rich layered look)
            const y2 = (height / 2) + Math.sin(x * 0.075 - wavePhase * 0.8) * (waveAmplitude * 0.65) * envelope;
            
            points.push(`${x},${y1}`);
            pointsBg.push(`${x},${y2}`);
        }
        
        waveformPath.setAttribute('d', `M ${points.join(' L ')}`);
        waveformPathBg.setAttribute('d', `M ${pointsBg.join(' L ')}`);
        
        wavePhase += 0.15;
        animationId = requestAnimationFrame(animateWave);
    }

    // Trigger simulation sequence
    function startSimulation() {
        if (isSimulating) return;
        isSimulating = true;
        
        // Clear text immediately, show cursor
        notepadBody.innerHTML = '<span class="typing-cursor"></span>';
        
        // Active bubble styling
        floatingBubble.classList.add('active');
        
        // Show floating waveform overlay pill
        overlayPill.classList.add('active');
        
        // Show bottom voice bar
        bottomVoiceBar.classList.add('active');
        
        // Start Waveform drawing
        waveAmplitude = 0;
        waveTargetAmplitude = 12; // High wave amplitude for recording state
        animateWave();
        
        // 1. Listening State (3 seconds)
        let timerCount = 0;
        voiceStatusText.textContent = `Listening... (00:00)`;
        
        const timerInterval = setInterval(() => {
            timerCount++;
            voiceStatusText.textContent = `Listening... (00:0${timerCount})`;
            if (timerCount >= 3) {
                clearInterval(timerInterval);
                transitionToTranscribing();
            }
        }, 1000);
    }
    
    // 2. Transcribing State
    function transitionToTranscribing() {
        voiceStatusText.textContent = 'Transcribing...';
        waveTargetAmplitude = 1.5; // low wiggle for processing state
        
        setTimeout(() => {
            // Hide bar and overlay
            overlayPill.classList.remove('active');
            bottomVoiceBar.classList.remove('active');
            floatingBubble.classList.remove('active');
            
            // Stop wave loop
            setTimeout(() => {
                isSimulating = false;
                cancelAnimationFrame(animationId);
            }, 500);
            
            // Start typing
            startTyping();
        }, 1500);
    }
    
    // 3. Typewriter Effect
    function startTyping() {
        let charIndex = 0;
        notepadBody.innerHTML = '<span class="typing-cursor"></span>';
        
        function typeNextChar() {
            if (charIndex < transcriptionText.length) {
                const currentText = transcriptionText.substring(0, charIndex + 1);
                notepadBody.innerHTML = currentText + '<span class="typing-cursor"></span>';
                charIndex++;
                setTimeout(typeNextChar, 35); // 35ms per character typing speed
            } else {
                // Done typing. Pause for 5 seconds and reset back to original
                setTimeout(resetNotepad, 5000);
            }
        }
        
        setTimeout(typeNextChar, 300);
    }
    
    // 4. Reset
    function resetNotepad() {
        if (isSimulating) return; // don't reset if user clicked again
        notepadBody.innerHTML = originalText + '<span class="typing-cursor"></span>';
    }

    // Attach click events
    floatingBubble.addEventListener('click', startSimulation);
}

/* 3. Setup Wizard Interactions */
function initSetupWizard() {
    // Step 1: Password toggle
    const toggleBtn = document.getElementById('btn-toggle-key');
    const inputField = document.getElementById('groq-key-input');
    const saveBtn = document.getElementById('btn-save-key');
    const saveStatus = document.getElementById('save-status');
    
    toggleBtn.addEventListener('click', () => {
        if (inputField.type === 'password') {
            inputField.type = 'text';
            toggleBtn.textContent = 'Hide';
        } else {
            inputField.type = 'password';
            toggleBtn.textContent = 'Show';
        }
    });
    
    // Step 1: Save key action
    saveBtn.addEventListener('click', () => {
        saveStatus.style.opacity = '1';
        saveBtn.textContent = 'Saved';
        setTimeout(() => {
            saveStatus.style.opacity = '0';
            saveBtn.textContent = 'Save';
        }, 3000);
    });
    
    // Step 3: Accessibility switch toggle
    const switchEl = document.getElementById('switch-service');
    switchEl.addEventListener('click', () => {
        switchEl.classList.toggle('active');
        const timelineCircle = document.getElementById('step-3-circle');
        if (switchEl.classList.contains('active')) {
            timelineCircle.classList.add('active');
        } else {
            timelineCircle.classList.remove('active');
        }
    });
}
