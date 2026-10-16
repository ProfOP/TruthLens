import {createRoot} from 'react-dom/client';
import {BrowserRouter, Route, Routes} from 'react-router-dom';
import {App} from './App';
import './styles.css';

createRoot(document.getElementById('root')!).render(
  <BrowserRouter>
    <Routes>
      <Route path="/analysis/:id" element={<App />} />
      <Route path="*" element={<App />} />
    </Routes>
  </BrowserRouter>
);
