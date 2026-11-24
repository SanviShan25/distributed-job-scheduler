import { useEffect, useState } from "react";
import { useLocation, useNavigate } from "react-router";

/**
 * Reads ?token= from the address once, then removes it from the address bar (a history *replace*,
 * so the token is not kept in the browser history, shown over someone's shoulder, or copied along
 * with the URL). The token stays in component state for the API call.
 */
export function useTokenFromUrl(): string | null {
  const location = useLocation();
  const navigate = useNavigate();
  const [token] = useState(() => new URLSearchParams(location.search).get("token"));

  useEffect(() => {
    if (new URLSearchParams(location.search).has("token")) {
      void navigate(
        { pathname: location.pathname, search: "", hash: location.hash },
        { replace: true, state: location.state as unknown },
      );
    }
  }, [location, navigate]);

  return token;
}
