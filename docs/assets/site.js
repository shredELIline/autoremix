(() => {
  const scrubber = document.querySelector('#transition-scrubber');
  const consoleView = document.querySelector('.transition-console');
  const label = document.querySelector('#scene-label');
  const time = document.querySelector('#scene-time');

  const updateTimeline = () => {
    const progress = Number(scrubber.value) / Number(scrubber.max);
    consoleView.style.setProperty('--playhead', `${progress * 100}%`);
    const seconds = Math.round(progress * 32);
    time.value = `00:${String(seconds).padStart(2, '0')}`;
    const state = progress < .28
      ? 'Source A arrangement'
      : progress < .66
        ? 'Layered procedural bridge'
        : 'Target B arrangement';
    label.textContent = state;
    scrubber.setAttribute('aria-valuetext', `${state}, ${seconds} seconds`);
  };

  scrubber.addEventListener('input', updateTimeline);
  updateTimeline();

  document.querySelectorAll('.app-shot img').forEach((image) => {
    image.addEventListener('error', () => {
      image.closest('.app-shot').classList.add('pending');
      image.setAttribute('aria-hidden', 'true');
    });
  });
})();
