import AsyncStorage from '@react-native-async-storage/async-storage';
import { z } from 'zod';

export function parseJson<T>(raw: string | null | undefined, fallback: T, isValid?: (value: unknown) => value is T): T { if (!raw) return fallback; try { const value: unknown = JSON.parse(raw); return isValid && !isValid(value) ? fallback : (value as T); } catch { return fallback; } }
export function isRecord(value: unknown): value is Record<string, unknown> { return typeof value === 'object' && value !== null && !Array.isArray(value); }
export function isActivity(value: unknown): value is { id: string; message: string; ok: boolean; at: number } { if (!isRecord(value)) return false; return typeof value.id === 'string' && typeof value.message === 'string' && typeof value.ok === 'boolean' && Number.isFinite(value.at); }
export function isActivityList(value: unknown): value is Array<{ id: string; message: string; ok: boolean; at: number }> { return Array.isArray(value) && value.every(isActivity); }
export const LinkItemSchema = z.object({ id: z.union([z.string(), z.number()]), url: z.string().min(1).max(4096), title: z.string().max(500).optional(), thumbnail: z.string().max(4096).optional(), channel_title: z.string().max(500).optional(), creator: z.string().max(500).optional() }).passthrough();
export const ManagedItemSchema = LinkItemSchema.extend({ localId: z.string().min(1).max(256), openedAt: z.number().finite(), closeAt: z.number().finite(), engageAt: z.number().finite(), engagementSent: z.boolean(), focused: z.boolean() });
export const ManagerStateSchema = z.object({ items: z.array(ManagedItemSchema).max(100), activity: z.array(z.object({ id: z.string(), message: z.string(), ok: z.boolean(), at: z.number().finite() })).max(200) }).passthrough();
export function isManagerState(value: unknown): value is z.infer<typeof ManagerStateSchema> { return ManagerStateSchema.safeParse(value).success; }
export async function readJsonStorage<T>(key: string, fallback: T, isValid?: (value: unknown) => value is T): Promise<T> { try { return parseJson(await AsyncStorage.getItem(key), fallback, isValid); } catch { return fallback; } }
export async function removeCorruptStorage(key: string): Promise<void> { try { await AsyncStorage.removeItem(key); } catch {} }
