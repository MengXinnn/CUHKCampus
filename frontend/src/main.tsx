import React from "react";
import ReactDOM from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import App from "./App";
import { RequestProgress } from "./components/RequestProgress";
import "@fontsource-variable/geist";
import "@fontsource-variable/noto-sans-sc";
import "./style.css";
import "katex/dist/katex.min.css";
const client = new QueryClient({
  defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
});
ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <QueryClientProvider client={client}>
      <BrowserRouter>
        <RequestProgress />
        <App />
      </BrowserRouter>
    </QueryClientProvider>
  </React.StrictMode>,
);
