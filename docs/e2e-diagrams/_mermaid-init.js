// Central Mermaid initializer — dark theme tuned for the diagram stylesheet.
// Loaded after mermaid.min.js on every diagram page so all sequence diagrams render consistently.
window.addEventListener('DOMContentLoaded', () => {
  if (!window.mermaid) {
    console.error('[e2e-diagrams] mermaid failed to load from CDN');
    return;
  }
  window.mermaid.initialize({
    startOnLoad: true,
    theme: 'base',
    themeVariables: {
      background: '#1c2130',
      primaryColor: '#1c2130',
      primaryTextColor: '#e6e8ef',
      primaryBorderColor: '#2a3042',
      lineColor: '#8ab4ff',
      secondaryColor: '#161923',
      tertiaryColor: '#0f1115',
      actorBkg: '#2a3042',
      actorBorder: '#8ab4ff',
      actorTextColor: '#e6e8ef',
      actorLineColor: '#9aa3b2',
      signalColor: '#e6e8ef',
      signalTextColor: '#e6e8ef',
      labelBoxBkgColor: '#0f1115',
      labelBoxBorderColor: '#2a3042',
      labelTextColor: '#e6e8ef',
      loopTextColor: '#e6e8ef',
      noteBkgColor: '#1f2433',
      noteBorderColor: '#f5a524',
      noteTextColor: '#e6e8ef',
      activationBorderColor: '#8ab4ff',
      activationBkgColor: '#2a3042',
      sequenceNumberColor: '#0f1115'
    },
    sequence: {
      actorMargin: 50,
      boxMargin: 10,
      messageMargin: 40,
      wrap: true,
      useMaxWidth: true
    }
  });
});
