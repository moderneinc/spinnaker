import type { IServerGroup } from '@spinnaker/core';

export interface IAzureRollbackSelection {
  serverGroup: IServerGroup;
  disabledServerGroups: IServerGroup[];
}

function inRollbackScope(selectedServerGroup: IServerGroup, candidate: IServerGroup): boolean {
  return (
    candidate.cluster === selectedServerGroup.cluster &&
    candidate.account === selectedServerGroup.account &&
    candidate.region === selectedServerGroup.region
  );
}

export function getAzureRollbackCandidates(
  selectedServerGroup: IServerGroup,
  allServerGroups: IServerGroup[],
): IServerGroup[] {
  return allServerGroups
    .filter(
      (candidate) => candidate.name !== selectedServerGroup.name && inRollbackScope(selectedServerGroup, candidate),
    )
    .sort((a, b) => b.name.localeCompare(a.name));
}

export function isAzureRollbackAvailable(selectedServerGroup: IServerGroup, allServerGroups: IServerGroup[]): boolean {
  return (
    !selectedServerGroup.isDisabled ||
    getAzureRollbackCandidates(selectedServerGroup, allServerGroups).some((candidate) => !candidate.isDisabled)
  );
}

/**
 * Rolling back a disabled server group means restoring it, so the group that must be disabled is an
 * enabled sibling, not the selection. Without this swap the operation disables the already-disabled
 * selection and leaves the enabled sibling serving traffic.
 */
export function selectAzureRollbackServerGroups(
  selectedServerGroup: IServerGroup,
  allServerGroups: IServerGroup[],
): IAzureRollbackSelection | undefined {
  let serverGroup = selectedServerGroup;

  if (selectedServerGroup.isDisabled) {
    serverGroup = getAzureRollbackCandidates(selectedServerGroup, allServerGroups)
      .filter((candidate) => !candidate.isDisabled)
      .sort(
        (a, b) =>
          (b.instanceCounts?.total || 0) - (a.instanceCounts?.total || 0) ||
          (b.createdTime || 0) - (a.createdTime || 0),
      )[0];

    if (!serverGroup) {
      return undefined;
    }
  }

  return {
    serverGroup,
    disabledServerGroups: getAzureRollbackCandidates(serverGroup, allServerGroups).filter(
      (candidate) => candidate.isDisabled,
    ),
  };
}
