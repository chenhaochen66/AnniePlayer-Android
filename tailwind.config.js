/** @type {import('tailwindcss').Config} */
module.exports = {
  content: [
    './app/**/*.{js,jsx,ts,tsx}',
    './components/**/*.{js,jsx,ts,tsx}',
  ],
  presets: [require('nativewind/preset')],
  theme: {
    extend: {
      colors: {
        glass: 'rgba(255,255,255,0.08)',
        'glass-border': 'rgba(255,255,255,0.05)',
      },
      backdropBlur: {
        xl: '40px',
      },
    },
  },
  plugins: [],
};
