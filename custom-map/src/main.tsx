import React from 'react';
import ReactDOM from 'react-dom/client';
import { setWorkerUrl } from 'maplibre-gl';
import workerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url';
import App from './App';
import './styles.css';
import 'maplibre-gl/dist/maplibre-gl.css';

setWorkerUrl(workerUrl);

if ('serviceWorker' in navigator && import.meta.env.PROD) {
  window.addEventListener('load', () => {
    void navigator.serviceWorker.register('/sw.js').catch((error) => {
      console.warn('[4Riders PWA] Service worker registration failed', error);
    });
  }, { once: true });
}
ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode><App /></React.StrictMode>
);