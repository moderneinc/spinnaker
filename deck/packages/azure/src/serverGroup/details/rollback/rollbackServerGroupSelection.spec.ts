import {
  getAzureRollbackCandidates,
  isAzureRollbackAvailable,
  selectAzureRollbackServerGroups,
} from './rollbackServerGroupSelection';

const sg = (name: string, overrides: any = {}): any => ({
  name,
  cluster: 'app-dev',
  account: 'test-account',
  region: 'westus',
  isDisabled: false,
  instanceCounts: { total: 1 },
  ...overrides,
});

describe('Azure rollback server group selection', () => {
  describe('getAzureRollbackCandidates', () => {
    it('excludes the selected server group from its own restore candidates', () => {
      const selected = sg('app-dev-v002', { isDisabled: true });

      const candidates = getAzureRollbackCandidates(selected, [selected, sg('app-dev-v001', { isDisabled: true })]);

      expect(candidates.map((c: any) => c.name)).toEqual(['app-dev-v001']);
    });

    it('excludes server groups from another account or region', () => {
      const selected = sg('app-dev-v003');
      const all = [
        selected,
        sg('app-dev-v002', { isDisabled: true, account: 'other-account' }),
        sg('app-dev-v001', { isDisabled: true, region: 'eastus' }),
        sg('app-dev-v000', { isDisabled: true }),
      ];

      expect(getAzureRollbackCandidates(selected, all).map((c: any) => c.name)).toEqual(['app-dev-v000']);
    });
  });

  describe('selectAzureRollbackServerGroups', () => {
    it('rolls back the selected server group when it is enabled', () => {
      const selected = sg('app-dev-v002');
      const previous = sg('app-dev-v001', { isDisabled: true });

      const selection = selectAzureRollbackServerGroups(selected, [selected, previous]);

      expect(selection.serverGroup.name).toBe('app-dev-v002');
      expect(selection.disabledServerGroups.map((c: any) => c.name)).toEqual(['app-dev-v001']);
    });

    // Rolling back a disabled group means restoring it, so the enabled sibling is the one that
    // must be disabled. Without the swap the operation disables the already-disabled selection
    // and leaves the enabled sibling serving traffic.
    it('rolls back the enabled sibling when the selected server group is disabled', () => {
      const selected = sg('app-dev-v001', { isDisabled: true });
      const running = sg('app-dev-v002');

      const selection = selectAzureRollbackServerGroups(selected, [selected, running]);

      expect(selection.serverGroup.name).toBe('app-dev-v002');
      expect(selection.disabledServerGroups.map((c: any) => c.name)).toContain('app-dev-v001');
    });

    it('prefers the largest enabled sibling to roll back', () => {
      const selected = sg('app-dev-v001', { isDisabled: true });
      const small = sg('app-dev-v002', { instanceCounts: { total: 1 } });
      const large = sg('app-dev-v003', { instanceCounts: { total: 4 } });

      const selection = selectAzureRollbackServerGroups(selected, [selected, small, large]);

      expect(selection.serverGroup.name).toBe('app-dev-v003');
    });

    it('declines to build a selection when a disabled group has no enabled sibling', () => {
      const selected = sg('app-dev-v001', { isDisabled: true });

      expect(
        selectAzureRollbackServerGroups(selected, [selected, sg('app-dev-v000', { isDisabled: true })]),
      ).toBeUndefined();
    });
  });

  describe('isAzureRollbackAvailable', () => {
    it('is available for an enabled server group', () => {
      const selected = sg('app-dev-v002');

      expect(isAzureRollbackAvailable(selected, [selected])).toBe(true);
    });

    it('is available for a disabled server group with an enabled sibling', () => {
      const selected = sg('app-dev-v001', { isDisabled: true });

      expect(isAzureRollbackAvailable(selected, [selected, sg('app-dev-v002')])).toBe(true);
    });

    it('is unavailable for a disabled server group whose siblings are all disabled', () => {
      const selected = sg('app-dev-v001', { isDisabled: true });

      expect(isAzureRollbackAvailable(selected, [selected, sg('app-dev-v000', { isDisabled: true })])).toBe(false);
    });
  });
});
