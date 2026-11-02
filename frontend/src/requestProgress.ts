let pending = 0;
const listeners = new Set<() => void>();
export const requestProgress = {
  subscribe(listener: () => void) {
    listeners.add(listener);
    return () => { listeners.delete(listener); };
  },
  snapshot: () => pending,
};
export function beginRequest() {
  pending++;
  listeners.forEach(listener => listener());
  return () => {
    pending--;
    listeners.forEach(listener => listener());
  };
}
