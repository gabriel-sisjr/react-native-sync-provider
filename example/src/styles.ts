import { Platform, StatusBar, StyleSheet } from 'react-native';

const monoFont = Platform.OS === 'ios' ? 'Courier' : 'monospace';

const shadow = {
  shadowColor: '#000',
  shadowOffset: { width: 0, height: 2 },
  shadowOpacity: 0.1,
  shadowRadius: 4,
  elevation: 3,
};

const styles = StyleSheet.create({
  /* --------------------------- screen containers -------------------------- */
  container: {
    flex: 1,
    backgroundColor: '#f5f5f5',
  },
  centeredContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: '#f5f5f5',
  },
  scrollView: {
    flex: 1,
    alignSelf: 'stretch',
  },
  content: {
    padding: 16,
    paddingBottom: 32,
  },

  /* --------------------------------- text --------------------------------- */
  title: {
    fontSize: 22,
    fontWeight: '700',
    color: '#333',
  },
  subtitle: {
    fontSize: 13,
    color: '#666',
    marginTop: 4,
    marginBottom: 12,
  },
  sectionTitle: {
    fontSize: 16,
    fontWeight: '700',
    color: '#333',
    marginBottom: 8,
  },
  label: {
    fontSize: 13,
    fontWeight: '600',
    color: '#444',
    marginBottom: 4,
  },
  helperText: {
    fontSize: 12,
    color: '#777',
    marginTop: 4,
  },
  mono: {
    fontFamily: monoFont,
    fontSize: 12,
    color: '#333',
  },
  emptyText: {
    textAlign: 'center',
    color: '#999',
    fontStyle: 'italic',
    paddingVertical: 24,
  },

  /* --------------------------------- card --------------------------------- */
  card: {
    backgroundColor: '#fff',
    borderRadius: 10,
    padding: 14,
    marginBottom: 12,
    ...shadow,
  },
  cardHeaderRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginBottom: 8,
  },

  /* ------------------------------ connection ------------------------------ */
  badge: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 6,
    paddingHorizontal: 10,
    borderRadius: 999,
    alignSelf: 'flex-start',
  },
  badgeDot: {
    width: 8,
    height: 8,
    borderRadius: 4,
    marginRight: 6,
  },
  badgeText: {
    fontSize: 12,
    fontWeight: '600',
  },
  badgeOnline: {
    backgroundColor: '#E8F5E9',
  },
  badgeOnlineText: {
    color: '#2E7D32',
  },
  badgeOnlineDot: {
    backgroundColor: '#4CAF50',
  },
  badgeOffline: {
    backgroundColor: '#FFEBEE',
  },
  badgeOfflineText: {
    color: '#C62828',
  },
  badgeOfflineDot: {
    backgroundColor: '#f44336',
  },
  badgeMetered: {
    backgroundColor: '#FFF8E1',
  },
  badgeMeteredText: {
    color: '#E65100',
  },
  badgeMeteredDot: {
    backgroundColor: '#FFB300',
  },
  badgeUnknown: {
    backgroundColor: '#ECEFF1',
  },
  badgeUnknownText: {
    color: '#455A64',
  },
  badgeUnknownDot: {
    backgroundColor: '#90A4AE',
  },

  /* ------------------------------ action row ------------------------------ */
  actionRow: {
    flexDirection: 'row',
    gap: 8,
    marginVertical: 8,
  },
  actionButton: {
    flex: 1,
  },

  /* ----------------------------- queue rows ------------------------------- */
  itemRow: {
    paddingVertical: 10,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: '#e0e0e0',
  },
  itemRowHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginBottom: 4,
  },
  itemMethod: {
    fontSize: 12,
    fontWeight: '700',
    color: '#1565C0',
    marginRight: 8,
  },
  itemUrl: {
    flex: 1,
    fontSize: 12,
    color: '#333',
    fontFamily: monoFont,
  },
  itemMetaRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginTop: 4,
  },
  itemMeta: {
    fontSize: 11,
    color: '#666',
  },
  removeButton: {
    paddingHorizontal: 8,
    paddingVertical: 4,
    borderRadius: 6,
    backgroundColor: '#FFEBEE',
  },
  removeButtonText: {
    color: '#C62828',
    fontSize: 11,
    fontWeight: '700',
  },

  /* ----------------------------- history rows ----------------------------- */
  historyRow: {
    paddingVertical: 10,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: '#e0e0e0',
  },
  historyHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  historyTimestamp: {
    fontSize: 12,
    color: '#444',
    fontFamily: monoFont,
  },
  historyCountsRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
  },
  historyCountSuccess: {
    fontSize: 12,
    fontWeight: '700',
    color: '#2E7D32',
  },
  historyCountFailed: {
    fontSize: 12,
    fontWeight: '700',
    color: '#C62828',
  },
  historyDetails: {
    marginTop: 8,
    padding: 10,
    backgroundColor: '#F5F5F5',
    borderRadius: 6,
  },
  historyDetailLine: {
    fontSize: 11,
    color: '#555',
    fontFamily: monoFont,
    marginBottom: 4,
  },
  historyDetailEmpty: {
    fontSize: 11,
    color: '#888',
    fontStyle: 'italic',
  },

  /* --------------------------------- form --------------------------------- */
  formField: {
    marginBottom: 12,
  },
  textInput: {
    borderWidth: 1,
    borderColor: '#CFD8DC',
    borderRadius: 8,
    paddingHorizontal: 10,
    paddingVertical: 8,
    backgroundColor: '#fff',
    fontSize: 13,
    color: '#222',
  },
  segmentedRow: {
    flexDirection: 'row',
    gap: 6,
    flexWrap: 'wrap',
  },
  segment: {
    paddingVertical: 6,
    paddingHorizontal: 12,
    borderRadius: 6,
    backgroundColor: '#ECEFF1',
    borderWidth: 1,
    borderColor: '#CFD8DC',
  },
  segmentSelected: {
    backgroundColor: '#2196F3',
    borderColor: '#1976D2',
  },
  segmentText: {
    fontSize: 12,
    fontWeight: '600',
    color: '#455A64',
  },
  segmentTextSelected: {
    color: '#fff',
  },
  switchRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingVertical: 4,
  },

  /* ----------------------------- save feedback ---------------------------- */
  savedToast: {
    marginTop: 8,
    padding: 8,
    borderRadius: 6,
    backgroundColor: '#E8F5E9',
  },
  savedToastText: {
    color: '#2E7D32',
    fontSize: 12,
    fontWeight: '600',
    textAlign: 'center',
  },

  /* ------------------------------ error banner ---------------------------- */
  errorBanner: {
    marginTop: 8,
    padding: 8,
    borderRadius: 6,
    backgroundColor: '#FFEBEE',
    borderLeftWidth: 3,
    borderLeftColor: '#C62828',
  },
  errorBannerText: {
    color: '#C62828',
    fontSize: 12,
  },

  /* --------------------------- header summary row ------------------------- */
  summaryRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginBottom: 4,
  },

  /* ------------------------------- helpers -------------------------------- */
  cardConstrained: {
    maxWidth: 360,
  },
  spinnerSpacing: {
    marginVertical: 8,
  },
  badgeSpacing: {
    marginTop: 12,
  },

  /* ------------------------------ tab bar --------------------------------- */
  tabContainer: {
    flexDirection: 'row',
    backgroundColor: '#fff',
    borderBottomWidth: 1,
    borderBottomColor: '#e0e0e0',
    alignSelf: 'stretch',
    paddingTop: Platform.OS === 'ios' ? 54 : (StatusBar.currentHeight ?? 0),
  },
  tab: {
    flex: 1,
    paddingVertical: 14,
    alignItems: 'center',
  },
  activeTab: {
    borderBottomWidth: 3,
    borderBottomColor: '#2196F3',
  },
  tabText: {
    fontSize: 14,
    fontWeight: '600',
    color: '#999',
  },
  activeTabText: {
    color: '#2196F3',
  },
});

export default styles;
